package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-007, SC-TRASH-007 (lecture, restauration), SC-BOARD-034, SC-BOARD-028 — DELETE
 * /api/boards/{id}, GET /api/trash/boards, POST /api/trash/boards/{id}/restore.
 */
class TrashBoardIT extends ApiTestBase {

  private static List<Integer> ids(JsonNode a) {
    List<Integer> out = new ArrayList<>();
    a.forEach(n -> out.add(n.get("id").asInt()));
    return out;
  }

  @Test
  @DisplayName(
      "SC-BOARD-007 — admin → 403 Seul le propriétaire peut supprimer le tableau. ; rien ne change")
  void adminCannotTrash() {
    assertError(
        delete(bruno(), "/api/boards/101"), 403, "Seul le propriétaire peut supprimer le tableau.");
    assertError(
        delete(carla(), "/api/boards/101"), 403, "Seul le propriétaire peut supprimer le tableau.");
    assertThat(scalar("SELECT deleted_at FROM taskora.board WHERE id = 101")).isNull();
  }

  @Test
  @DisplayName(
      "SC-BOARD-007 / 028 — propriétaire → 204 sans corps ; deleted_at en heure murale de Paris, deleted_by ; invisible pour tous")
  void ownerTrashes() {
    Response r = delete(alice(), "/api/boards/101");
    assertStatus(r, 204);
    assertThat(r.asString()).isEmpty();
    Map<String, Object> row =
        query("SELECT deleted_at, deleted_by_id FROM taskora.board WHERE id = 101").getFirst();
    assertThat(String.valueOf(row.get("deleted_at"))).isEqualTo("2026-11-27T11:00");
    assertThat(row.get("deleted_by_id")).isEqualTo(Fixture.ALICE);
    assertThat(ids(json(get(carla(), "/api/boards")))).doesNotContain(101);
    assertError(get(carla(), "/api/boards/101"), 404, "Ce tableau est dans la corbeille.");
    assertError(get(nadia(), "/api/boards/101"), 404, "Tableau introuvable.");
    assertThat(count("SELECT COUNT(*) FROM taskora.board_list WHERE board_id = 101"))
        .as("contenu conservé")
        .isEqualTo(5);
  }

  @Test
  @DisplayName(
      "SC-TRASH-007 — corbeille du propriétaire seulement : deleted_at DESC puis id DESC, deletedBy, purgeAt = +30 jours")
  void trashList() {
    assertStatus(delete(alice(), "/api/boards/101"), 204);
    Response r = get(alice(), "/api/trash/boards");
    assertStatus(r, 200);
    JsonNode list = json(r);
    assertThat(ids(list)).containsExactly(101, Fixture.DRAFT_TRASHED, Fixture.OLD_TRASHED);
    JsonNode launch = list.get(0);
    assertThat(launch.get("title").asString()).isEqualTo(Fixture.LAUNCH_TITLE);
    assertThat(launch.get("background").asString()).isEqualTo("ocean");
    assertAtom(launch.get("deletedAt"), "2026-11-27T11:00:00+01:00");
    assertAtom(launch.get("purgeAt"), "2026-12-27T11:00:00+01:00");
    assertThat(launch.get("deletedBy").asString()).isEqualTo("Alice Martin");
    assertThat(list.get(1).get("deletedBy").isNull()).as("deleted_by_id NULL").isTrue();
    assertAtom(list.get(2).get("deletedAt"), "2026-11-25T09:30:00+01:00");
    assertAtom(list.get(2).get("purgeAt"), "2026-12-25T09:30:00+01:00");
    assertThat(json(get(bruno(), "/api/trash/boards"))).as("admin : pas propriétaire").isEmpty();
    assertThat(json(get(nadia(), "/api/trash/boards"))).isEmpty();
  }

  @Test
  @DisplayName("SC-BOARD-007 — événement BoardTrashed (broker, HELD)")
  void trashedEvent() {
    assertStatus(delete(alice(), "/api/boards/101"), 204);
    assertThat(outbox("broker"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.get("type")).isEqualTo("BoardTrashed");
          assertThat(e.get("status")).isEqualTo("HELD");
          assertThat(((Number) e.get("aggregate_id")).intValue()).isEqualTo(101);
        });
  }

  @Test
  @DisplayName(
      "SC-TRASH-007 — restauration : non-membre → 404 pas dans la corbeille ; admin → 403 ; propriétaire → 200 {id}")
  void restore() {
    assertError(
        post(nadia(), "/api/trash/boards/103/restore", null),
        404,
        "Ce tableau n’est pas dans la corbeille.");
    assertError(
        post(bruno(), "/api/trash/boards/103/restore", null),
        403,
        "Seul le propriétaire du tableau peut le restaurer ou le supprimer.");
    assertError(
        post(carla(), "/api/trash/boards/103/restore", null),
        403,
        "Seul le propriétaire du tableau peut le restaurer ou le supprimer.");
    Response r = post(alice(), "/api/trash/boards/103/restore", null);
    assertStatus(r, 200);
    assertThat(json(r).toString()).isEqualTo("{\"id\":103}");
    assertThat(
            query("SELECT deleted_at, deleted_by_id FROM taskora.board WHERE id = 103").getFirst())
        .containsEntry("deleted_at", null)
        .containsEntry("deleted_by_id", null);
    assertThat(ids(json(get(carla(), "/api/boards")))).contains(103);
    assertThat(json(get(bruno(), "/api/boards/103")).get("lists")).hasSize(1);
    assertThat(outbox("broker")).extracting(e -> e.get("type")).containsExactly("BoardRestored");
  }

  @Test
  @DisplayName(
      "SC-TRASH-007 — tableau non supprimé ou inexistant → 404 Ce tableau n’est pas dans la corbeille.")
  void notInTrash() {
    assertError(
        post(alice(), "/api/trash/boards/101/restore", null),
        404,
        "Ce tableau n’est pas dans la corbeille.");
    assertError(
        post(alice(), "/api/trash/boards/" + Fixture.MISSING_BOARD + "/restore", null),
        404,
        "Ce tableau n’est pas dans la corbeille.");
  }
}
