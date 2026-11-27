package com.taskora.board.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.OpenApiContracts;
import com.taskora.board.support.SymfonyStub;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-025 (B-ADR-5) — notifications composées par board-service, écrites dans l'outbox dans la
 * transaction métier, livrées à POST /internal/notifications (WireMock) après validation, rejouées
 * si Symfony est indisponible, jamais pour une requête refusée.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationOutboxIT extends ApiTestBase {
  private static final String ADD = "/api/boards/101/members";
  private static final String DORA = "{\"email\":\"dora@exemple.test\"}";

  private DeliverPendingMessagesUseCase relay() {
    return bean(DeliverPendingMessagesUseCase.class);
  }

  private static JsonNode byType(List<LoggedRequest> calls, String type) {
    for (LoggedRequest c : calls) {
      JsonNode n = json(c.getBodyAsString());
      if (type.equals(n.get("type").asString())) {
        return n;
      }
    }
    throw new AssertionError("aucune notification " + type);
  }

  private static Set<Integer> recipients(JsonNode n) {
    Set<Integer> out = new HashSet<>();
    n.get("recipientIds").forEach(r -> out.add(r.asInt()));
    return out;
  }

  @Test
  @DisplayName(
      "SC-BOARD-025 — ajout de Dora : board_invite à Dora, member_joined aux autres membres, jamais à l'acteur, textes exacts")
  void addMemberNotifications() {
    assertStatus(post(alice(), ADD, DORA), 201);
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() == 2);
    List<LoggedRequest> calls = SymfonyStub.notifications();
    for (LoggedRequest c : calls) {
      OpenApiContracts.assertNotificationRequestConforms(
          c.getBodyAsString(), BOARD_TO_SYMFONY_SECRET);
    }
    JsonNode invite = byType(calls, "board_invite");
    assertThat(recipients(invite)).containsExactly(Fixture.DORA);
    assertThat(invite.get("message").asString())
        .isEqualTo("Alice Martin vous a ajouté au tableau « Lancement produit »");
    assertThat(invite.get("actorId").asInt()).isEqualTo(Fixture.ALICE);
    assertThat(invite.get("boardId").asInt()).isEqualTo(101);
    JsonNode joined = byType(calls, "member_joined");
    assertThat(recipients(joined))
        .as("autres membres, sans l'acteur ni le nouveau membre")
        .containsExactlyInAnyOrder(Fixture.BRUNO, Fixture.CARLA, Fixture.MARC);
    assertThat(joined.get("message").asString())
        .isEqualTo("Dora Libre a rejoint le tableau « Lancement produit »");
    assertThat(joined.get("actorId").asInt()).isEqualTo(Fixture.ALICE);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.get("status")).isEqualTo("DELIVERED")));
  }

  @Test
  @DisplayName(
      "SC-BOARD-025 — Symfony indisponible 2 minutes (503) : 201 quand même, puis livraison après rétablissement, une fois chacune")
  void symfonyDownThenUp() {
    SymfonyStub.reset();
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(503);
    assertStatus(post(alice(), ADD, DORA), 201);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .hasSize(2)
                .allSatisfy(m -> {
                  assertThat(m.get("status")).isEqualTo("PENDING");
                  assertThat(((Number) m.get("attempts")).intValue()).isEqualTo(1);
                }));
    CLOCK.advance(Duration.ofSeconds(60));
    relay().deliverDue(50);
    CLOCK.advance(Duration.ofSeconds(60));
    relay().deliverDue(50);
    SymfonyStub.reset();
    CLOCK.advance(Duration.ofMinutes(5));
    relay().deliverDue(50);
    assertThat(SymfonyStub.notifications()).hasSize(2);
    assertThat(outbox("symfony-notifications"))
        .allSatisfy(m -> assertThat(m.get("status")).isEqualTo("DELIVERED"));
    relay().deliverDue(50);
    assertThat(SymfonyStub.notifications())
        .as("une seule livraison réussie par message")
        .hasSize(2);
  }

  @Test
  @DisplayName(
      "SC-BOARD-025 — rejeu après délai dépassé : même eventId renvoyé (idempotence côté Symfony)")
  void replaySameEventId() {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answerAfter(200, 4_500);
    assertStatus(delete(bruno(), "/api/boards/101/members/" + Fixture.CARLA), 204);
    await().atMost(Duration.ofSeconds(15)).until(() -> SymfonyStub.notifications().size() == 1);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .singleElement()
                .satisfies(m -> assertThat(m.get("status")).isEqualTo("PENDING")));
    SymfonyStub.reset();
    CLOCK.advance(Duration.ofMinutes(1));
    relay().deliverDue(50);
    List<LoggedRequest> calls = SymfonyStub.notifications();
    assertThat(calls).hasSize(1);
    String replayed = json(calls.getFirst().getBodyAsString()).get("eventId").asString();
    assertThat(replayed).isEqualTo(outbox("symfony-notifications").getFirst().get("event_id"));
  }

  @Test
  @DisplayName("SC-BOARD-025 — requête refusée (409 déjà membre) → aucun message d'outbox")
  void conflictWritesNothing() {
    assertError(
        post(alice(), ADD, "{\"email\":\"carla@exemple.test\"}"),
        409,
        "Cet utilisateur est déjà membre du tableau.");
    assertThat(outbox()).isEmpty();
    assertThat(SymfonyStub.notifications()).isEmpty();
  }

  @Test
  @DisplayName(
      "SC-BOARD-025 — Symfony 401 (secret faux) → FAILED, journal ERROR sans donnée personnelle, aucune boucle")
  void unauthorizedIsFinal(CapturedOutput output) {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(401);
    assertStatus(post(alice(), ADD, DORA), 201);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.get("status")).isEqualTo("FAILED")));
    int calls = SymfonyStub.notifications().size();
    CLOCK.advance(Duration.ofHours(1));
    relay().deliverDue(50);
    assertThat(SymfonyStub.notifications()).as("pas de nouvelle tentative").hasSize(calls);
    assertThat(output.getAll()).contains("ERROR");
    assertThat(output.getAll())
        .doesNotContain("dora@exemple.test")
        .doesNotContain("Dora Libre")
        .doesNotContain("Lancement produit")
        .doesNotContain(BOARD_TO_SYMFONY_SECRET);
    for (Map<String, Object> m : outbox("symfony-notifications")) {
      assertThat(String.valueOf(m.get("last_error"))).doesNotContain("@").doesNotContain("Dora");
    }
  }

  @Test
  @DisplayName("SC-BOARD-025 — 409 de Symfony → FAILED (table complète : OutboxRelayIT)")
  void classification() {
    SymfonyStub.server().resetMappings();
    SymfonyStub.answer(409);
    assertStatus(post(alice(), ADD, DORA), 201);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .allSatisfy(m -> assertThat(m.get("status")).isEqualTo("FAILED")));
  }
}
