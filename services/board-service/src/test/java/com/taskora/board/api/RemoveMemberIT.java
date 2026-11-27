package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.SymfonyStub;
import io.restassured.response.Response;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-015, 016, 025 (member_removed), 035 — DELETE /api/boards/{id}/members/{userId}. */
class RemoveMemberIT extends ApiTestBase {
  private static String member(int userId) {
    return "/api/boards/101/members/" + userId;
  }

  @Test
  @DisplayName(
      "SC-BOARD-015 — retrait par un admin : 204, désassigné des cartes du tableau seulement, adhésion supprimée")
  void removal() {
    Response r = delete(bruno(), member(Fixture.CARLA));
    assertStatus(r, 204);
    assertThat(r.asString()).isEmpty();
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                Fixture.CARLA))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.card_assignee WHERE user_id = ? AND card_id IN (401, 406)",
                Fixture.CARLA))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.card_assignee WHERE user_id = ? AND card_id = 409",
                Fixture.CARLA))
        .as("assignation d'un autre tableau conservée")
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM taskora.card_assignee WHERE user_id = ?", Fixture.MARC))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-015 / 025 — member_removed au membre retiré, texte exact, sans tableau associé, acteur = appelant")
  void notification() {
    assertStatus(delete(bruno(), member(Fixture.CARLA)), 204);
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() == 1);
    LoggedRequest call = SymfonyStub.notifications().getFirst();
    JsonNode body = json(call.getBodyAsString());
    assertThat(body.get("type").asString()).isEqualTo("member_removed");
    assertThat(body.get("message").asString())
        .isEqualTo("Bruno Admin vous a retiré du tableau « Lancement produit »");
    assertThat(body.get("actorId").asInt()).isEqualTo(Fixture.BRUNO);
    assertThat(body.get("recipientIds").toString()).isEqualTo("[3]");
    assertThat(body.get("boardId") == null || body.get("boardId").isNull())
        .as("member_removed sans tableau (FA13)")
        .isTrue();
    assertThat(outbox("broker"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.get("type")).isEqualTo("MemberRemoved");
          assertThat(json(String.valueOf(e.get("payload"))).get("self").asBoolean()).isFalse();
        });
  }

  @Test
  @DisplayName(
      "SC-BOARD-016 — quitter soi-même : 204, aucune notification, le tableau disparaît de son accueil")
  void leave() {
    assertStatus(delete(carla(), member(Fixture.CARLA)), 204);
    assertThat(outbox("symfony-notifications")).isEmpty();
    bean(DeliverPendingMessagesUseCase.class).deliverDue(50);
    assertThat(SymfonyStub.notifications()).isEmpty();
    assertThat(json(get(carla(), "/api/boards")).toString()).doesNotContain("\"id\":101");
    assertThat(outbox("broker"))
        .singleElement()
        .satisfies(e ->
            assertThat(json(String.valueOf(e.get("payload"))).get("self").asBoolean()).isTrue());
  }

  @Test
  @DisplayName("SC-BOARD-010 — un simple membre ne retire pas autrui → 403 gestion")
  void memberCannotRemoveOthers() {
    assertError(
        delete(marc(), member(Fixture.CARLA)),
        403,
        "Action réservée aux administrateurs du tableau.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-015 / 016 — propriétaire visé ou qui part → 403 Le propriétaire ne peut pas quitter son tableau.")
  void ownerStays() {
    assertError(
        delete(alice(), member(Fixture.ALICE)),
        403,
        "Le propriétaire ne peut pas quitter son tableau.");
    assertError(
        delete(bruno(), member(Fixture.ALICE)),
        403,
        "Le propriétaire ne peut pas quitter son tableau.");
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND role = 'owner'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-035 (E28) — membre visé inexistant ou non membre → 404 Membre introuvable.")
  void unknownTarget() {
    assertError(delete(alice(), member(Fixture.NADIA)), 404, "Membre introuvable.");
    assertError(delete(alice(), member(Fixture.MISSING_USER)), 404, "Membre introuvable.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-016 (surchargé E1) — quitter un tableau dont on n'est pas membre → 404 Tableau introuvable.")
  void nonMemberLeaving() {
    assertError(delete(nadia(), member(Fixture.NADIA)), 404, "Tableau introuvable.");
  }

  @Test
  @DisplayName("SC-BOARD-015 — le membre retiré reçoit 404 dès sa requête suivante")
  void removedGets404() {
    String carla = carla();
    assertStatus(delete(alice(), member(Fixture.CARLA)), 204);
    assertError(get(carla, "/api/boards/101"), 404, "Tableau introuvable.");
    assertThat(json(get(carla, "/api/boards")).toString()).doesNotContain(Fixture.LAUNCH_TITLE);
  }
}
