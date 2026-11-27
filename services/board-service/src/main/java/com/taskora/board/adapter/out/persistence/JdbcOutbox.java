package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.PendingMessage;
import com.taskora.board.domain.event.BoardCreated;
import com.taskora.board.domain.event.DomainEvent;
import com.taskora.board.domain.event.MemberAdded;
import com.taskora.board.domain.event.MemberRemoved;
import com.taskora.board.domain.event.MemberRoleChanged;
import com.taskora.board.domain.event.NotificationRequested;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox {@code board.outbox_message} (B-ADR-5, board-migration.md §2) écrite dans la transaction
 * métier. Les notifications ({@code symfony-notifications}, {@code PENDING}) déclenchent une
 * livraison asynchrone juste après la validation ; les événements de domaine ({@code broker}) sont
 * écrits en {@code HELD} (U7-a). Instants en UTC.
 */
public class JdbcOutbox implements OutboxPort {
  static final String NOTIFICATIONS = "symfony-notifications";
  static final String BROKER = "broker";

  /** Bail d'un message réservé par un relais (bien au-delà du délai de lecture de 3 s). */
  static final Duration LEASE = Duration.ofSeconds(30);

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DELIVERY_SCHEDULED = JdbcOutbox.class.getName() + ".delivery";

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Runnable afterCommit;

  /**
   * Crée l'outbox.
   *
   * @param jdbc accès JDBC
   * @param clock horloge (UTC)
   * @param afterCommit livraison asynchrone, lancée une fois par transaction validée qui a écrit
   *     une notification
   */
  public JdbcOutbox(JdbcClient jdbc, Clock clock, Runnable afterCommit) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.afterCommit = afterCommit;
  }

  @Override
  public void append(DomainEvent event) {
    Map<String, @Nullable Object> payload = new LinkedHashMap<>();
    String type = event.getClass().getSimpleName();
    payload.put("eventId", event.eventId().toString());
    payload.put("type", type);
    payload.put("version", 1);
    payload.put(
        "occurredAt",
        DateTimeFormatter.ISO_INSTANT.format(event.occurredAt().truncatedTo(ChronoUnit.SECONDS)));
    payload.put("boardId", event.boardId().value());
    payload.put("actorId", id(event.actorId()));
    switch (event) {
      case BoardCreated created -> payload.put("ownerId", created.ownerId().value());
      case MemberAdded added -> {
        payload.put("userId", added.userId().value());
        payload.put("role", added.role().value());
        payload.put("via", added.viaInvitation() ? "invitation" : "direct");
      }
      case MemberRoleChanged changed -> {
        payload.put("userId", changed.userId().value());
        payload.put("role", changed.role().value());
      }
      case MemberRemoved removed -> {
        payload.put("userId", removed.userId().value());
        payload.put("self", removed.self());
      }
      default -> {
        // BoardTrashed, BoardRestored : enveloppe seule.
      }
    }
    insert(
        event.eventId(),
        type,
        BROKER,
        event.boardId(),
        JSON.writeValueAsString(payload),
        event.occurredAt(),
        "HELD");
  }

  @Override
  public void append(NotificationRequested notification) {
    append(notification, notification.boardId() == null ? new BoardId(0) : notification.boardId());
  }

  @Override
  public void append(NotificationRequested notification, BoardId aggregate) {
    Map<String, @Nullable Object> payload = new LinkedHashMap<>();
    payload.put("eventId", notification.eventId().toString());
    payload.put("type", notification.type().value());
    payload.put("actorId", id(notification.actorId()));
    payload.put("recipientIds", notification.recipientIds().stream().map(UserId::value).toList());
    payload.put("message", notification.message());
    payload.put("boardId", notification.boardId() == null ? null : notification.boardId().value());
    Instant now = clock.instant();
    insert(
        notification.eventId(),
        "NotificationRequested",
        NOTIFICATIONS,
        aggregate,
        JSON.writeValueAsString(payload),
        now,
        "PENDING");
    scheduleDeliveryAfterCommit();
  }

  private void insert(
      UUID eventId,
      String type,
      String destination,
      BoardId aggregate,
      String payload,
      Instant occurredAt,
      String status) {
    LocalDateTime at = utc(occurredAt);
    jdbc.sql(
            "INSERT INTO board.outbox_message (event_id, type, destination, aggregate_id,"
                + " payload, occurred_at, available_at, status, attempts)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")
        .param(eventId.toString())
        .param(type)
        .param(destination)
        .param(aggregate.value())
        .param(payload)
        .param(at)
        .param(at)
        .param(status)
        .update();
  }

  private void scheduleDeliveryAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.hasResource(DELIVERY_SCHEDULED)) {
      return;
    }
    TransactionSynchronizationManager.bindResource(DELIVERY_SCHEDULED, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            afterCommit.run();
          }

          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(DELIVERY_SCHEDULED);
          }
        });
  }

  /**
   * Réserve un lot de messages dus : {@code SELECT … FOR UPDATE SKIP LOCKED} puis échéance
   * repoussée d'un bail ({@link #LEASE}) dans la même transaction. La livraison se fait ensuite
   * HORS transaction (aucun appel réseau pendant un verrou) ; un autre relais ne reprend le message
   * qu'après expiration du bail (rejeu idempotent par {@code eventId} côté Symfony).
   */
  @Override
  public List<PendingMessage> lockDue(Instant now, int limit) {
    List<PendingMessage> due =
        jdbc.sql(
                "SELECT id, event_id, type, destination, payload, attempts"
                    + " FROM board.outbox_message"
                    + " WHERE destination = ? AND status = 'PENDING' AND available_at <= ?"
                    + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")
            .param(NOTIFICATIONS)
            .param(utc(now))
            .param(limit)
            .query((rs, n) ->
                new PendingMessage(
                    rs.getLong("id"),
                    UUID.fromString(SqlRows.text(rs, "event_id")),
                    SqlRows.text(rs, "type"),
                    SqlRows.text(rs, "destination"),
                    SqlRows.text(rs, "payload"),
                    rs.getInt("attempts")))
            .list();
    LocalDateTime leaseEnd = utc(now.plus(LEASE));
    for (PendingMessage message : due) {
      jdbc.sql("UPDATE board.outbox_message SET available_at = ? WHERE id = ?")
          .param(leaseEnd)
          .param(message.id())
          .update();
    }
    return due;
  }

  @Override
  public void markDelivered(long id, Instant at) {
    jdbc.sql(
            "UPDATE board.outbox_message SET status = 'DELIVERED', delivered_at = ?,"
                + " attempts = attempts + 1, last_error = NULL WHERE id = ?")
        .param(utc(at))
        .param(id)
        .update();
  }

  @Override
  public void markFailed(long id, String error) {
    jdbc.sql(
            "UPDATE board.outbox_message SET status = 'FAILED', attempts = attempts + 1,"
                + " last_error = ? WHERE id = ? AND status = 'PENDING'")
        .param(error)
        .param(id)
        .update();
  }

  @Override
  public void reschedule(long id, Instant next, String error) {
    jdbc.sql(
            "UPDATE board.outbox_message SET available_at = ?, attempts = attempts + 1,"
                + " last_error = ? WHERE id = ? AND status = 'PENDING'")
        .param(utc(next))
        .param(error)
        .param(id)
        .update();
  }

  @Override
  public int purgeDeliveredBefore(Instant before) {
    return jdbc.sql(
            "DELETE FROM board.outbox_message WHERE status = 'DELIVERED' AND delivered_at < ?")
        .param(utc(before))
        .update();
  }

  private static @Nullable Integer id(@Nullable UserId user) {
    return user == null ? null : user.value();
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
