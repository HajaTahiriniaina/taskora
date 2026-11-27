package com.taskora.board.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-032 (B-ADR-4 point 3, V4) — propriétaire unique sous 100 opérations concurrentes :
 * rétrogradations croisées d'admins, tentatives sur le propriétaire ; aucune 5xx ; à la fin,
 * exactement un owner, égal à board.owner_id.
 */
@Tag("concurrency")
class OwnerInvariantConcurrencyIT extends ApiTestBase {

  @Test
  @DisplayName(
      "SC-BOARD-032 — Bob rétrograde Carla pendant que Carla rétrograde Bob, puis Bob se rétrograde : les trois réussissent")
  void crossDemotion() throws Exception {
    BoardDatabase.rootExecute(
        "UPDATE taskora.board_member SET role = 'admin' WHERE board_id = 101 AND user_id = 3");
    List<Integer> s =
        ConcurrentRequests.together(
            List.of(
                () ->
                    raw("PATCH", bruno(), "/api/boards/101/members/3", "{\"role\":\"member\"}")
                        .status(),
                () ->
                    raw("PATCH", carla(), "/api/boards/101/members/2", "{\"role\":\"member\"}")
                        .status()));
    assertThat(s).allMatch(x -> x == 200 || x == 403);
    assertThat(s).doesNotContain(500);
    int self = raw("PATCH", bruno(), "/api/boards/101/members/2", "{\"role\":\"member\"}").status();
    assertThat(self).isIn(200, 403);
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND role = 'owner'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT user_id FROM taskora.board_member WHERE board_id = 101 AND role = 'owner'"))
        .isEqualTo(Fixture.ALICE);
  }

  @Test
  @DisplayName(
      "SC-BOARD-032 / V4 — 100 opérations concurrentes mêlées : 0 × 5xx, exactement un owner = board.owner_id")
  void hundredOperations() throws Exception {
    BoardDatabase.rootExecute(
        "UPDATE taskora.board_member SET role = 'admin' WHERE board_id = 101 AND user_id = 3");
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      int k = i;
      tasks.add(() ->
          switch (k % 5) {
            case 0 ->
                raw(
                        "PATCH",
                        bruno(),
                        "/api/boards/101/members/3",
                        "{\"role\":\"" + (k % 2 == 0 ? "member" : "admin") + "\"}")
                    .status();
            case 1 ->
                raw(
                        "PATCH",
                        carla(),
                        "/api/boards/101/members/2",
                        "{\"role\":\"" + (k % 2 == 0 ? "admin" : "member") + "\"}")
                    .status();
            case 2 ->
                raw("PATCH", bruno(), "/api/boards/101/members/1", "{\"role\":\"member\"}")
                    .status();
            case 3 -> raw("DELETE", carla(), "/api/boards/101/members/1", null).status();
            default ->
                raw(
                        "PATCH",
                        alice(),
                        "/api/boards/101/members/" + (k % 2 == 0 ? 2 : 3),
                        "{\"role\":\"admin\"}")
                    .status();
          });
    }
    List<Integer> statuses = new ArrayList<>();
    for (int from = 0; from < tasks.size(); from += 20) {
      statuses.addAll(ConcurrentRequests.together(tasks.subList(from, from + 20)));
    }
    assertThat(statuses).allMatch(s -> s < 500);
    assertThat(statuses).doesNotContain(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND role = 'owner'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM taskora.board b JOIN taskora.board_member m ON m.board_id = b.id"
                    + " AND m.user_id = b.owner_id AND m.role = 'owner' WHERE b.id = 101"))
        .isEqualTo(1);
  }
}
