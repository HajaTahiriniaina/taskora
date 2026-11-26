package com.taskora.identity.adapter.out.persistence;

import com.taskora.identity.application.port.out.InvitationClaim;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PendingClaim;
import com.taskora.identity.domain.event.DomainEvent;
import com.taskora.identity.domain.event.UserProfileUpdated;
import com.taskora.identity.domain.event.UserRegistered;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.UserId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox transactionnelle (A11) : charges au format du contrat interne (UserRegistered,
 * UserProfileUpdated, InvitationClaimRequest), dates en UTC.
 */
public class JpaOutboxAdapter implements OutboxPort {
  static final String DESTINATION_SYMFONY = "symfony-invitations";
  static final String DESTINATION_BROKER = "broker";
  static final String TYPE_CLAIM = "InvitationClaimRequested";
  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_HELD = "HELD";
  private static final int LAST_ERROR_MAX = 255;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter OCCURRED_AT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

  private final OutboxJpaRepository repository;

  public JpaOutboxAdapter(OutboxJpaRepository repository) {
    this.repository = repository;
  }

  /** Statut HELD, destination broker (pas de transport en phase 1, U2). */
  @Override
  public void appendEvent(DomainEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", event.eventId().toString());
    payload.put("version", 1);
    payload.put("occurredAt", OCCURRED_AT.format(event.occurredAt().atOffset(ZoneOffset.UTC)));
    payload.put("userId", event.userId().value());
    String type =
        switch (event) {
          case UserRegistered registered -> {
            payload.put("email", registered.email());
            payload.put("name", registered.name());
            payload.put("avatarColor", registered.avatarColor());
            yield "UserRegistered";
          }
          case UserProfileUpdated updated -> {
            payload.put("name", updated.name());
            payload.put("avatarColor", updated.avatarColor());
            payload.put("changed", updated.changed());
            yield "UserProfileUpdated";
          }
        };
    payload.put("type", type);
    repository.save(
        new OutboxMessageEntity(
            event.eventId().toString(),
            type,
            DESTINATION_BROKER,
            event.userId().value(),
            JSON.writeValueAsString(payload),
            utc(event.occurredAt()),
            utc(event.occurredAt()),
            STATUS_HELD));
  }

  @Override
  public void appendInvitationClaim(InvitationClaim claim, Instant availableAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", claim.eventId().toString());
    payload.put("userId", claim.userId().value());
    payload.put("email", claim.email().value());
    repository.save(
        new OutboxMessageEntity(
            claim.eventId().toString(),
            TYPE_CLAIM,
            DESTINATION_SYMFONY,
            claim.userId().value(),
            JSON.writeValueAsString(payload),
            utc(availableAt),
            utc(availableAt),
            STATUS_PENDING));
  }

  @Override
  public List<PendingClaim> lockDueClaims(Instant now, int limit) {
    return repository.lockDue(DESTINATION_SYMFONY, utc(now), limit).stream()
        .map(JpaOutboxAdapter::toPending)
        .toList();
  }

  @Override
  public Optional<PendingClaim> lockClaim(UUID eventId) {
    return repository.lockPending(eventId.toString()).map(JpaOutboxAdapter::toPending);
  }

  @Override
  public void markDelivered(UUID eventId, Instant at) {
    repository.markDelivered(eventId.toString(), utc(at));
  }

  @Override
  public void markRetry(UUID eventId, int attempts, Instant nextAttemptAt, String errorCode) {
    repository.markRetry(eventId.toString(), attempts, utc(nextAttemptAt), truncate(errorCode));
  }

  @Override
  public void markFailed(UUID eventId, String errorCode) {
    repository.markFailed(eventId.toString(), truncate(errorCode));
  }

  private static PendingClaim toPending(OutboxMessageEntity entity) {
    JsonNode payload = JSON.readTree(entity.getPayload());
    InvitationClaim claim =
        new InvitationClaim(
            UUID.fromString(entity.getEventId()),
            new UserId(entity.getAggregateId()),
            Email.ofStored(payload.get("email").asString()));
    return new PendingClaim(claim, entity.getAttempts());
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static String truncate(String error) {
    return error.length() > LAST_ERROR_MAX ? error.substring(0, LAST_ERROR_MAX) : error;
  }
}
