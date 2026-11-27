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
 * SC-BOARD-017, SC-BOARD-031 (B-ADR-4 point 3) — N ajouts / N invitations simultanés (casses
 * différentes) : une seule ligne, N−1 × 409, jamais 500 ; répété pour éprouver les courses.
 */
@Tag("concurrency")
class MembershipConcurrencyIT extends ApiTestBase {
  private static final int N = 8;
  private static final int ROUNDS = 10;

  private List<Integer> statuses(List<String> bodies) throws Exception {
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < bodies.size(); i++) {
      String token = i % 2 == 0 ? alice() : bruno();
      String body = bodies.get(i);
      tasks.add(() -> raw("POST", token, "/api/boards/101/members", body).status());
    }
    return ConcurrentRequests.together(tasks);
  }

  @Test
  @DisplayName(
      "SC-BOARD-017 — N ajouts simultanés du même compte : 1 adhésion, 1 × 201, N−1 × 409, 0 × 500")
  void concurrentAdds() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      BoardDatabase.loadReference();
      List<String> bodies = new ArrayList<>();
      for (int i = 0; i < N; i++) {
        bodies.add(
            "{\"email\":\"" + (i % 2 == 0 ? "DORA@exemple.test" : "dora@EXEMPLE.test") + "\"}");
      }
      List<Integer> s = statuses(bodies);
      assertThat(s).as("tour %d : %s", round, s).filteredOn(x -> x == 201).hasSize(1);
      assertThat(s).filteredOn(x -> x == 409).hasSize(N - 1);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                  Fixture.DORA))
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-031 — N invitations simultanées de « Nouveau@Exemple.test » (casses différentes) : 1 invitation, 1 × 202, N−1 × 409")
  void concurrentInvitations() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      BoardDatabase.loadReference();
      List<String> bodies = new ArrayList<>();
      for (int i = 0; i < N; i++) {
        String email =
            switch (i % 3) {
              case 0 -> " Nouveau@Exemple.test ";
              case 1 -> "nouveau@exemple.test";
              default -> "NOUVEAU@EXEMPLE.TEST";
            };
        bodies.add("{\"email\":\"" + email + "\"}");
      }
      List<Integer> s = statuses(bodies);
      assertThat(s).as("tour %d : %s", round, s).filteredOn(x -> x == 202).hasSize(1);
      assertThat(s).filteredOn(x -> x == 409).hasSize(N - 1);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM taskora.invitation WHERE board_id = 101 AND email = 'nouveau@exemple.test'"))
          .isEqualTo(1);
    }
  }
}
