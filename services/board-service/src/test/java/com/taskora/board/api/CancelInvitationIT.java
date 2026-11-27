package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SC-BOARD-012, E28 — DELETE /api/boards/{id}/invitations/{invId}. */
class CancelInvitationIT extends ApiTestBase {

  @Test
  @DisplayName("SC-BOARD-012 — invitation du tableau → 204 sans corps, supprimée")
  void cancel() {
    Response r = delete(alice(), "/api/boards/101/invitations/" + Fixture.INVITATION_INES_LAUNCH);
    assertStatus(r, 204);
    assertThat(r.asString()).isEmpty();
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.invitation WHERE id = ?",
                Fixture.INVITATION_INES_LAUNCH))
        .isZero();
    assertThat(json(get(alice(), "/api/boards/101")).get("invitations")).hasSize(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-012 (E28) — invitation d'un autre tableau → 404 Ressource introuvable., rien supprimé (pas d'IDOR)")
  void otherBoardInvitation() {
    assertError(
        delete(alice(), "/api/boards/101/invitations/" + Fixture.INVITATION_EVE_BOARD),
        404,
        "Ressource introuvable.");
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.invitation WHERE id = ?",
                Fixture.INVITATION_EVE_BOARD))
        .isEqualTo(1);
    assertError(
        delete(alice(), "/api/boards/101/invitations/" + Fixture.INVITATION_ORPHAN),
        404,
        "Ressource introuvable.");
  }

  @Test
  @DisplayName("SC-BOARD-012 — invitation inexistante → 404 Ressource introuvable.")
  void missing() {
    assertError(
        delete(bruno(), "/api/boards/101/invitations/99999"), 404, "Ressource introuvable.");
  }

  @Test
  @DisplayName("SC-BOARD-010 — simple membre → 403 gestion, invitation conservée")
  void memberForbidden() {
    assertError(
        delete(marc(), "/api/boards/101/invitations/" + Fixture.INVITATION_INES_LAUNCH),
        403,
        "Action réservée aux administrateurs du tableau.");
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.invitation WHERE id = ?",
                Fixture.INVITATION_INES_LAUNCH))
        .isEqualTo(1);
  }
}
