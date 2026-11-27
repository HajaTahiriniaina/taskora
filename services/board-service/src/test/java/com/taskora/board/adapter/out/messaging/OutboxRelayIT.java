package com.taskora.board.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.SymfonyStub;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * B-ADR-5 point 2 — relais de l'outbox : {@code FOR UPDATE SKIP LOCKED} (deux relais ne livrent
 * jamais deux fois), backoff 5 s → 5 min, classement 200 / 400 / 401 / 404 / 409 / 5xx / délai,
 * {@code HELD} jamais envoyé, purge des {@code DELIVERED} de plus de 30 jours. Messages insérés
 * directement dans {@code board.outbox_message} (schéma : board-migration.md §2, Flyway V1).
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayIT extends ApiTestBase {

  private DeliverPendingMessagesUseCase relay() {
    return bean(DeliverPendingMessagesUseCase.class);
  }

  private static LocalDateTime utc(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  /** Insère un message de notification dû maintenant. */
  private static String insert(String status, int attempts, Instant availableAt) {
    String eventId = UUID.randomUUID().toString();
    String payload =
        "{\"eventId\":\""
            + eventId
            + "\",\"type\":\"board_invite\",\"actorId\":1,\"recipientIds\":[6],"
            + "\"message\":\"Alice Martin vous a ajouté au tableau « Lancement produit »\",\"boardId\":101}";
    BoardDatabase.rootExecute(
        "INSERT INTO board.outbox_message (event_id, type, destination, aggregate_id, payload,"
            + " occurred_at, available_at, status, attempts) VALUES (?, 'NotificationRequested',"
            + " 'symfony-notifications', 101, ?, ?, ?, ?, ?)",
        eventId,
        payload,
        utc(Fixture.NOW),
        utc(availableAt),
        status,
        attempts);
    return eventId;
  }

  private static Map<String, Object> message(String eventId) {
    return BoardDatabase.query("SELECT * FROM board.outbox_message WHERE event_id = ?", eventId)
        .getFirst();
  }

  private static Duration delayUntilNext(String eventId) {
    LocalDateTime next = (LocalDateTime) message(eventId).get("available_at");
    return Duration.between(utc(CLOCK.instant()), next);
  }

  @ParameterizedTest(name = "Symfony {0} → {1}")
  @CsvSource({
    "200,DELIVERED",
    "400,FAILED",
    "401,FAILED",
    "409,FAILED",
    "404,PENDING",
    "500,PENDING",
    "503,PENDING"
  })
  @DisplayName("B-ADR-5 — classement des réponses de Symfony")
  void classification(int status, String expected) {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(status);
    String id = insert("PENDING", 0, Fixture.NOW);
    relay().deliverDue(50);
    Map<String, Object> m = message(id);
    assertThat(m.get("status")).isEqualTo(expected);
    assertThat(((Number) m.get("attempts")).intValue()).isEqualTo(1);
    if ("DELIVERED".equals(expected)) {
      assertThat(m.get("delivered_at")).isNotNull();
    } else {
      assertThat(String.valueOf(m.get("last_error"))).doesNotContain("@").doesNotContain("Alice");
    }
  }

  @Test
  @DisplayName(
      "B-ADR-5 — délai de lecture dépassé (> 3 s) → rejouable, sans bloquer au-delà du délai")
  void readTimeout() {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answerAfter(200, 4_500);
    String id = insert("PENDING", 0, Fixture.NOW);
    long t0 = System.nanoTime();
    relay().deliverDue(50);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(4_400));
    assertThat(message(id).get("status")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName(
      "B-ADR-5 — backoff : 5 s après le premier échec, croissant, plafonné à 5 min ; rien avant l'échéance")
  void backoff() {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(503);
    String id = insert("PENDING", 0, Fixture.NOW);
    relay().deliverDue(50);
    assertThat(delayUntilNext(id)).isEqualTo(Duration.ofSeconds(5));
    int calls = SymfonyStub.notifications().size();
    relay().deliverDue(50);
    assertThat(SymfonyStub.notifications()).as("pas avant l'échéance").hasSize(calls);
    Duration previous = Duration.ofSeconds(5);
    for (int i = 0; i < 12; i++) {
      CLOCK.advance(delayUntilNext(id));
      relay().deliverDue(50);
      Duration next = delayUntilNext(id);
      assertThat(next).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(Duration.ofMinutes(5));
      previous = next;
    }
    assertThat(previous).isEqualTo(Duration.ofMinutes(5));
    assertThat(message(id).get("status")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName(
      "B-ADR-5 — au-delà de 20 tentatives : journal WARN (sans donnée personnelle), toujours rejouable")
  void stuckWarning(CapturedOutput output) {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(503);
    String id = insert("PENDING", 20, Fixture.NOW);
    relay().deliverDue(50);
    assertThat(message(id).get("status")).isEqualTo("PENDING");
    assertThat(output.getAll())
        .contains("WARN")
        .doesNotContain("Alice Martin")
        .doesNotContain("@exemple.test");
  }

  @Test
  @DisplayName(
      "B-ADR-5 — deux relais simultanés (SKIP LOCKED) : 40 messages livrés exactement une fois")
  void twoRelays() throws Exception {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answerAfter(200, 50);
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 40; i++) {
      ids.add(insert("PENDING", 0, Fixture.NOW));
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> runs =
          List.of(
              pool.submit(() -> {
                start.await();
                return relay().deliverDue(50);
              }),
              pool.submit(() -> {
                start.await();
                return relay().deliverDue(50);
              }));
      start.countDown();
      for (Future<Integer> f : runs) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    List<String> sent =
        SymfonyStub.notifications().stream()
            .map(c -> json(c.getBodyAsString()).get("eventId").asString())
            .toList();
    assertThat(sent).hasSize(40).doesNotHaveDuplicates();
    assertThat(Set.copyOf(sent)).isEqualTo(ids);
    assertThat(
            BoardDatabase.count(
                "SELECT COUNT(*) FROM board.outbox_message WHERE status = 'DELIVERED'"))
        .isEqualTo(40);
  }

  @Test
  @DisplayName("U7-a — messages HELD (broker) jamais envoyés")
  void heldNeverSent() {
    String id = insert("HELD", 0, Fixture.NOW);
    BoardDatabase.rootExecute(
        "UPDATE board.outbox_message SET destination = 'broker' WHERE event_id = ?", id);
    relay().deliverDue(50);
    assertThat(SymfonyStub.notifications()).isEmpty();
    assertThat(message(id).get("status")).isEqualTo("HELD");
  }

  @Test
  @DisplayName(
      "B-ADR-5 — purge : DELIVERED de plus de 30 jours supprimés ; récents, PENDING et FAILED conservés")
  void purge() {
    String old = insert("DELIVERED", 1, Fixture.NOW);
    String recent = insert("DELIVERED", 1, Fixture.NOW);
    String pending = insert("PENDING", 3, Fixture.NOW.plus(Duration.ofDays(1)));
    String failed = insert("FAILED", 1, Fixture.NOW);
    BoardDatabase.rootExecute(
        "UPDATE board.outbox_message SET delivered_at = ? WHERE event_id = ?",
        utc(Fixture.NOW.minus(Duration.ofDays(31))),
        old);
    BoardDatabase.rootExecute(
        "UPDATE board.outbox_message SET delivered_at = ? WHERE event_id = ?",
        utc(Fixture.NOW.minus(Duration.ofDays(29))),
        recent);
    BoardDatabase.rootExecute(
        "UPDATE board.outbox_message SET occurred_at = ? WHERE event_id IN (?, ?)",
        utc(Fixture.NOW.minus(Duration.ofDays(60))),
        pending,
        failed);
    assertThat(relay().purgeDelivered()).isEqualTo(1);
    List<Object> left =
        BoardDatabase.query("SELECT event_id FROM board.outbox_message").stream()
            .map(r -> r.get("event_id"))
            .toList();
    assertThat(left).containsExactlyInAnyOrder(recent, pending, failed);
  }
}
