package com.taskora.board.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-028 (B-ADR-11, R4) — dates écrites par board-service relues en SQL brut = heure murale
 * de Paris (comme Symfony) ; dates écrites par Symfony (jeu de référence) exposées en ATOM avec le
 * décalage de Paris ; purgeAt = deletedAt + 30 jours.
 */
class DateCompatibilityIT extends ApiTestBase {

  private static LocalDateTime raw(String sql, Object... args) {
    return (LocalDateTime) query(sql, args).getFirst().values().iterator().next();
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — été : createdAt et joinedAt 12:00+02:00 ; SQL brut 12:00:00 (heure murale)")
  void summerWrite() {
    CLOCK.set(Instant.parse("2026-07-01T10:00:00Z"));
    Response r =
        post(
            TOKENS.identity(Fixture.ALICE_EMAIL, Fixture.ALICE),
            "/api/boards",
            "{\"title\":\"Été\"}");
    assertStatus(r, 201);
    JsonNode b = json(r);
    assertAtom(b.get("createdAt"), "2026-07-01T12:00:00+02:00");
    assertAtom(b.get("members").get(0).get("joinedAt"), "2026-07-01T12:00:00+02:00");
    int id = b.get("id").asInt();
    assertThat(raw("SELECT created_at FROM taskora.board WHERE id = ?", id))
        .isEqualTo(LocalDateTime.parse("2026-07-01T12:00:00"));
    assertThat(raw("SELECT joined_at FROM taskora.board_member WHERE board_id = ?", id))
        .isEqualTo(LocalDateTime.parse("2026-07-01T12:00:00"));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — hiver : invitation et corbeille écrites 11:00:00 (Paris), exposées +01:00, purgeAt +30 j")
  void winterWrites() {
    CLOCK.set(Instant.parse("2026-11-27T10:00:00.900Z"));
    assertStatus(
        post(alice(), "/api/boards/101/members", "{\"email\":\"hiver@exemple.test\"}"), 202);
    assertThat(raw("SELECT created_at FROM taskora.invitation WHERE email = 'hiver@exemple.test'"))
        .as("secondes entières")
        .isEqualTo(LocalDateTime.parse("2026-11-27T11:00:00"));
    assertStatus(delete(alice(), "/api/boards/101"), 204);
    assertThat(raw("SELECT deleted_at FROM taskora.board WHERE id = 101"))
        .isEqualTo(LocalDateTime.parse("2026-11-27T11:00:00"));
    JsonNode trashed = json(get(alice(), "/api/trash/boards")).get(0);
    assertAtom(trashed.get("deletedAt"), "2026-11-27T11:00:00+01:00");
    assertAtom(trashed.get("purgeAt"), "2026-12-27T11:00:00+01:00");
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — dates écrites par Symfony relues à l'identique : été +02:00, hiver +01:00, heure ambiguë d'octobre +02:00")
  void symfonyWrittenDates() {
    JsonNode b = json(get(alice(), "/api/boards/101"));
    assertAtom(b.get("createdAt"), "2026-07-14T09:15:00+02:00");
    assertAtom(b.get("members").get(2).get("joinedAt"), "2026-11-21T14:00:00+01:00");
    assertAtom(b.get("invitations").get(1).get("createdAt"), "2026-10-25T02:30:00+02:00");
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — corbeille écrite pendant l'heure d'été : purgeAt suit le calendrier (+30 jours, décalage d'arrivée)")
  void purgeAcrossDst() {
    CLOCK.set(Instant.parse("2026-10-10T10:00:00Z"));
    assertStatus(delete(alice(), "/api/boards/101"), 204);
    JsonNode trashed = json(get(alice(), "/api/trash/boards")).get(0);
    assertAtom(trashed.get("deletedAt"), "2026-10-10T12:00:00+02:00");
    assertAtom(trashed.get("purgeAt"), "2026-11-09T12:00:00+01:00");
  }
}
