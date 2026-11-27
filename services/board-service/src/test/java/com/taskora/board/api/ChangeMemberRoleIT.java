package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-014, 032, 035 (E28) — PATCH /api/boards/{id}/members/{userId}. */
class ChangeMemberRoleIT extends ApiTestBase {
  private static String member(int userId) {
    return "/api/boards/101/members/" + userId;
  }

  private static String role(int userId) {
    return scalar(
        "SELECT role FROM taskora.board_member WHERE board_id = 101 AND user_id = ?", userId);
  }

  @Test
  @DisplayName("SC-BOARD-014 — un admin promeut un membre → 200 Member exact")
  void promote() {
    Response r = patch(bruno(), member(Fixture.CARLA), "{\"role\":\"admin\"}");
    assertStatus(r, 200);
    JsonNode m = json(r);
    assertThat(m.get("id").asInt()).isEqualTo(Fixture.CARLA);
    assertThat(m.get("name").asString()).isEqualTo("Carla Membre");
    assertThat(m.get("email").asString()).isEqualTo(Fixture.CARLA_EMAIL);
    assertThat(m.get("role").asString()).isEqualTo("admin");
    assertAtom(m.get("joinedAt"), "2026-11-21T14:00:00+01:00");
    assertThat(role(Fixture.CARLA)).isEqualTo("admin");
  }

  @Test
  @DisplayName(
      "SC-BOARD-014 — propriétaire visé → 403 Le rôle du propriétaire ne peut pas être modifié.")
  void ownerTargeted() {
    assertError(
        patch(bruno(), member(Fixture.ALICE), "{\"role\":\"member\"}"),
        403,
        "Le rôle du propriétaire ne peut pas être modifié.");
    assertThat(role(Fixture.ALICE)).isEqualTo("owner");
  }

  @ParameterizedTest(name = "role = {0}")
  @ValueSource(strings = {"\"owner\"", "\"\"", "42", "null", "\"Admin\"", "true"})
  @DisplayName(
      "SC-BOARD-014 / 032 — rôle hors admin/member (owner, vide, non chaîne) → 400 Rôle invalide.")
  void invalidRole(String value) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(
        patch(alice(), member(Fixture.CARLA), "{\"role\":" + value + "}"), 400, "Rôle invalide.");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName("SC-BOARD-014 — role absent → 400 Rôle invalide.")
  void absentRole() {
    assertError(patch(alice(), member(Fixture.CARLA), "{}"), 400, "Rôle invalide.");
  }

  @Test
  @DisplayName("SC-BOARD-035 (E28) — compte non membre ou inexistant → 404 Membre introuvable.")
  void unknownTarget() {
    assertError(
        patch(alice(), member(Fixture.NADIA), "{\"role\":\"admin\"}"), 404, "Membre introuvable.");
    assertError(
        patch(alice(), member(Fixture.MISSING_USER), "{\"role\":\"admin\"}"),
        404,
        "Membre introuvable.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-014 — ordre du contrat : membre visé (404) puis propriétaire (403) puis rôle (400)")
  void checkOrder() {
    assertError(
        patch(alice(), member(Fixture.NADIA), "{\"role\":\"owner\"}"), 404, "Membre introuvable.");
    assertError(
        patch(bruno(), member(Fixture.ALICE), "{\"role\":\"owner\"}"),
        403,
        "Le rôle du propriétaire ne peut pas être modifié.");
  }

  @Test
  @DisplayName("SC-BOARD-010 — simple membre → 403 gestion")
  void memberForbidden() {
    assertError(
        patch(marc(), member(Fixture.CARLA), "{\"role\":\"admin\"}"),
        403,
        "Action réservée aux administrateurs du tableau.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-014 / 032 — un admin se rétrograde lui-même ; il perd la gestion à la requête suivante")
  void selfDemotion() {
    assertStatus(patch(bruno(), member(Fixture.BRUNO), "{\"role\":\"member\"}"), 200);
    assertThat(role(Fixture.BRUNO)).isEqualTo("member");
    assertError(
        patch(bruno(), member(Fixture.CARLA), "{\"role\":\"admin\"}"),
        403,
        "Action réservée aux administrateurs du tableau.");
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND role = 'owner'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("SC-BOARD-014 — événement MemberRoleChanged (broker, HELD)")
  void event() {
    assertStatus(patch(alice(), member(Fixture.CARLA), "{\"role\":\"admin\"}"), 200);
    assertThat(outbox("broker"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.get("type")).isEqualTo("MemberRoleChanged");
          JsonNode p = json(String.valueOf(e.get("payload")));
          assertThat(p.get("userId").asInt()).isEqualTo(Fixture.CARLA);
          assertThat(p.get("role").asString()).isEqualTo("admin");
        });
  }
}
