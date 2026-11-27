package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.OpenApiContracts;
import com.taskora.board.support.SymfonyStub;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-030, SC-IDENT-005, SC-BOARD-013, E24 — POST /internal/invitations/claim servi par
 * board-service : même contrat que identity-service.internal.yaml (réponses validées contre
 * board-service.internal.yaml), idempotent, notifications board_invite par l'outbox.
 */
class ClaimInvitationsIT extends ApiTestBase {

  private static String body(String eventId, Object userId, String email) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"userId\":"
        + userId
        + ",\"email\":\""
        + email
        + "\"}";
  }

  private Response claimInes(String eventId) {
    return claim(IDENTITY_TO_BOARD_SECRET, body(eventId, Fixture.INES, Fixture.INES_EMAIL));
  }

  private static List<Integer> ints(JsonNode a) {
    List<Integer> out = new ArrayList<>();
    a.forEach(n -> out.add(n.asInt()));
    return out;
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — 2 invitations dont une sur un tableau dans la corbeille → 200 claimed 2, adhésions member, invitations supprimées")
  void claimTwoBoards() {
    Response r = claimInes(UUID.randomUUID().toString());
    assertStatus(r, 200);
    JsonNode b = json(r);
    assertThat(b.get("claimed").asInt()).isEqualTo(2);
    assertThat(ints(b.get("boardIds")))
        .containsExactlyInAnyOrder(Fixture.LAUNCH, Fixture.OLD_TRASHED);
    assertThat(
            query(
                "SELECT board_id, role FROM taskora.board_member WHERE user_id = ? ORDER BY board_id",
                Fixture.INES))
        .containsExactly(
            Map.of("board_id", Fixture.LAUNCH, "role", "member"),
            Map.of("board_id", Fixture.OLD_TRASHED, "role", "member"));
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'invite@exemple.test'"))
        .isZero();
    assertThat(
            json(get(TOKENS.identity(Fixture.INES_EMAIL, Fixture.INES), "/api/boards")).toString())
        .as("tableau ouvert visible dès /boards")
        .contains("\"id\":101")
        .doesNotContain("\"id\":103");
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 / 025 — une notification board_invite par tableau, acteur = inviteur, texte exact, corps conforme")
  void notifications() {
    assertStatus(claimInes(UUID.randomUUID().toString()), 200);
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() == 2);
    List<String> messages = new ArrayList<>();
    for (LoggedRequest call : SymfonyStub.notifications()) {
      OpenApiContracts.assertNotificationRequestConforms(
          call.getBodyAsString(), BOARD_TO_SYMFONY_SECRET);
      JsonNode n = json(call.getBodyAsString());
      assertThat(n.get("type").asString()).isEqualTo("board_invite");
      assertThat(n.get("actorId").asInt()).isEqualTo(Fixture.ALICE);
      assertThat(n.get("recipientIds").toString()).isEqualTo("[9]");
      messages.add(n.get("message").asString());
    }
    assertThat(messages)
        .containsExactlyInAnyOrder(
            "Alice Martin vous a ajouté au tableau « Lancement produit »",
            "Alice Martin vous a ajouté au tableau « Ancien projet »");
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — rejeu (même message ou nouveau) → 200 {claimed:0, boardIds:[]}, aucun doublon, aucune notification")
  void idempotent() {
    String eventId = UUID.randomUUID().toString();
    assertStatus(claimInes(eventId), 200);
    long notificationsBefore = outbox("symfony-notifications").size();
    Response replay = claimInes(eventId);
    assertStatus(replay, 200);
    assertThat(json(replay).toString()).isEqualTo("{\"claimed\":0,\"boardIds\":[]}");
    assertThat(json(claimInes(UUID.randomUUID().toString())).get("claimed").asInt()).isZero();
    assertThat(count("SELECT COUNT(*) FROM taskora.board_member WHERE user_id = ?", Fixture.INES))
        .isEqualTo(2);
    assertThat(outbox("symfony-notifications")).hasSize((int) notificationsBefore);
  }

  @Test
  @DisplayName("SC-BOARD-030 — inviteur supprimé → « Quelqu'un vous a ajouté… », actorId null")
  void unknownInviter() {
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at)"
            + " VALUES (20, 'orphelin@exemple.test', 'Olga Orpheline', '$2y$13$factice', '#000000', '[]',"
            + " '2026-11-27 10:59:00')");
    Response r =
        claim(
            IDENTITY_TO_BOARD_SECRET,
            body(UUID.randomUUID().toString(), 20, "orphelin@exemple.test"));
    assertStatus(r, 200);
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() == 1);
    JsonNode n = json(SymfonyStub.notifications().getFirst().getBodyAsString());
    assertThat(n.get("message").asString())
        .isEqualTo("Quelqu'un vous a ajouté au tableau « Import Trello »");
    assertThat(n.get("actorId").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — déjà membre : invitation supprimée, pas d'adhésion en double, pas de notification")
  void alreadyMember() {
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.invitation (email, created_at, board_id, invited_by_id)"
            + " VALUES ('dora@exemple.test', '2026-11-26 10:00:00', 101, 1)");
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.board_member (role, favorite, joined_at, board_id, user_id)"
            + " VALUES ('admin', 0, '2026-11-26 11:00:00', 101, 6)");
    Response r =
        claim(
            IDENTITY_TO_BOARD_SECRET,
            body(UUID.randomUUID().toString(), Fixture.DORA, Fixture.DORA_EMAIL));
    assertStatus(r, 200);
    assertThat(json(r).get("claimed").asInt()).isZero();
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'dora@exemple.test'"))
        .isZero();
    assertThat(scalar("SELECT role FROM taskora.board_member WHERE board_id = 101 AND user_id = 6"))
        .isEqualTo("admin");
    assertThat(outbox("symfony-notifications")).isEmpty();
  }

  @Test
  @DisplayName(
      "SC-BOARD-013 / E24 — invitation créée avec role admin, ancienne d'un an : rejointe comme member")
  void e24AndNoExpiry() {
    assertStatus(
        post(
            alice(),
            "/api/boards/102/members",
            "{\"email\":\"futur@exemple.test\",\"role\":\"admin\"}"),
        202);
    BoardDatabase.rootExecute(
        "UPDATE taskora.invitation SET created_at = '2025-11-01 08:00:00' WHERE email = 'futur@exemple.test'");
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at)"
            + " VALUES (21, 'futur@exemple.test', 'Fabien Futur', '$2y$13$factice', '#111111', '[]',"
            + " '2026-11-27 10:59:00')");
    Response r =
        claim(
            IDENTITY_TO_BOARD_SECRET, body(UUID.randomUUID().toString(), 21, "futur@exemple.test"));
    assertStatus(r, 200);
    assertThat(
            scalar("SELECT role FROM taskora.board_member WHERE board_id = 102 AND user_id = 21"))
        .isEqualTo("member");
    assertThat(outbox("broker"))
        .filteredOn(e -> "MemberAdded".equals(e.get("type")))
        .singleElement()
        .satisfies(e ->
            assertThat(json(String.valueOf(e.get("payload"))).get("via").asString())
                .isEqualTo("invitation"));
  }

  @Test
  @DisplayName("SC-BOARD-030 — e-mail de casse différente : égal pour la collation → 200")
  void emailCollation() {
    Response r =
        claim(
            IDENTITY_TO_BOARD_SECRET,
            body(UUID.randomUUID().toString(), Fixture.INES, "INVITE@Exemple.test"));
    assertStatus(r, 200);
    assertThat(json(r).get("claimed").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — utilisateur absent → 404 Utilisateur introuvable. (rejouable) ; e-mail différent → 409")
  void unknownUserAndMismatch() {
    assertError(
        claim(IDENTITY_TO_BOARD_SECRET, body(UUID.randomUUID().toString(), 4242, "x@exemple.test")),
        404,
        "Utilisateur introuvable.");
    assertError(
        claim(
            IDENTITY_TO_BOARD_SECRET,
            body(UUID.randomUUID().toString(), Fixture.INES, "autre@exemple.test")),
        409,
        "L'e-mail ne correspond pas à l'utilisateur.");
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'autre@exemple.test'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — secret absent ou faux → 401 Accès interne refusé., rien n'est consommé ; un JWT ne remplace jamais le secret")
  void secret() {
    String b = body(UUID.randomUUID().toString(), Fixture.INES, Fixture.INES_EMAIL);
    assertError(claim(null, b), 401, "Accès interne refusé.");
    assertError(claim("faux-secret-0123456789abcdef0123456789", b), 401, "Accès interne refusé.");
    Response withJwt =
        internal()
            .header("Authorization", "Bearer " + alice())
            .contentType("application/json")
            .body(b)
            .post("/internal/invitations/claim");
    assertError(withJwt, 401, "Accès interne refusé.");
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'invite@exemple.test'"))
        .isEqualTo(2);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "pas du json|JSON invalide.",
        "[]|JSON invalide.",
        "\"x\"|JSON invalide.",
        "{\"userId\":9,\"email\":\"invite@exemple.test\"}|Le champ \"eventId\" est obligatoire.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"email\":\"invite@exemple.test\"}|Le champ \"userId\" est obligatoire.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"userId\":9}|Le champ \"email\" est obligatoire.",
        "{\"eventId\":\"pas-un-uuid\",\"userId\":9,\"email\":\"invite@exemple.test\"}|Le champ \"eventId\" est invalide.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"userId\":\"9\",\"email\":\"invite@exemple.test\"}|Le champ \"userId\" est invalide.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"userId\":0,\"email\":\"invite@exemple.test\"}|Le champ \"userId\" est invalide.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"userId\":9,\"email\":\"\"}|Le champ \"email\" est obligatoire.",
        "{\"eventId\":\"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c\",\"userId\":9,\"email\":42}|Le champ \"email\" est invalide."
      })
  @DisplayName("SC-BOARD-030 — corps invalide → 400 exact (non rejouable), rien n'est consommé")
  void invalidBody(String body, String message) {
    assertError(claim(IDENTITY_TO_BOARD_SECRET, body), 400, message);
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'invite@exemple.test'"))
        .isEqualTo(2);
  }
}
