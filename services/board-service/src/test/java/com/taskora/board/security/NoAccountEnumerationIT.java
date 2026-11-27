package com.taskora.board.security;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-035 (E28 option b) — pas d'énumération des comptes : « existant non membre » et «
 * inexistant » donnent des réponses identiques (statut, corps, en-têtes) ; idem pour les
 * invitations d'un autre tableau.
 */
class NoAccountEnumerationIT extends ApiTestBase {

  @Test
  @DisplayName("SC-BOARD-035 — PATCH membre : compte existant non membre ≡ compte inexistant")
  void patchMember() {
    String body = "{\"role\":\"admin\"}";
    assertIndistinguishable(
        patch(alice(), "/api/boards/101/members/" + Fixture.NADIA, body),
        patch(alice(), "/api/boards/101/members/" + Fixture.MISSING_USER, body),
        "PATCH membre");
    assertError(
        patch(alice(), "/api/boards/101/members/" + Fixture.EVE, body), 404, "Membre introuvable.");
  }

  @Test
  @DisplayName("SC-BOARD-035 — DELETE membre : compte existant non membre ≡ compte inexistant")
  void deleteMember() {
    assertIndistinguishable(
        delete(alice(), "/api/boards/101/members/" + Fixture.NADIA),
        delete(alice(), "/api/boards/101/members/" + Fixture.MISSING_USER),
        "DELETE membre");
  }

  @Test
  @DisplayName(
      "SC-BOARD-035 — DELETE invitation : invitation d'un autre tableau ≡ invitation inexistante")
  void deleteInvitation() {
    assertIndistinguishable(
        delete(alice(), "/api/boards/101/invitations/" + Fixture.INVITATION_EVE_BOARD),
        delete(alice(), "/api/boards/101/invitations/99999"),
        "DELETE invitation");
  }

  @Test
  @DisplayName(
      "SC-BOARD-035 — POST membre : un compte existant n'est pas révélé à un simple membre (403 avant toute recherche)")
  void addMemberByMember() {
    assertIndistinguishable(
        post(marc(), "/api/boards/101/members", "{\"email\":\"dora@exemple.test\"}"),
        post(marc(), "/api/boards/101/members", "{\"email\":\"personne@exemple.test\"}"),
        "POST membre par un simple membre");
  }
}
