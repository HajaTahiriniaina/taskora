package com.taskora.board.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-033 (B-ADR-4 point 3, V5) — retrait d'un membre pendant qu'on l'assigne : T2 n'existe
 * pas encore, l'assignation concurrente est simulée par une transaction JDBC (compte board) qui
 * prend {@code LOCK IN SHARE MODE} sur l'adhésion puis insère l'assignation. Sur 100 exécutions :
 * jamais d'assigné non membre, jamais de 5xx.
 */
@Tag("concurrency")
class RemoveVsAssignConcurrencyIT extends ApiTestBase {
  private static final int RUNS = 100;

  /** Assignation simulée : 1 si insérée, 0 si l'adhésion n'existe plus. */
  private static int assignCarlaTo(int cardId) throws SQLException {
    try (Connection c = BoardDatabase.boardConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement lock =
          c.prepareStatement(
              "SELECT id FROM taskora.board_member WHERE board_id = 101 AND user_id = ? LOCK IN SHARE MODE")) {
        lock.setInt(1, Fixture.CARLA);
        try (ResultSet rs = lock.executeQuery()) {
          if (!rs.next()) {
            c.rollback();
            return 0;
          }
        }
        try (PreparedStatement ins =
            c.prepareStatement(
                "INSERT IGNORE INTO taskora.card_assignee (card_id, user_id) VALUES (?, ?)")) {
          ins.setInt(1, cardId);
          ins.setInt(2, Fixture.CARLA);
          ins.executeUpdate();
        }
        c.commit();
        return 1;
      } catch (SQLException e) {
        c.rollback();
        if (e.getErrorCode() == 1213 || e.getErrorCode() == 1205) {
          return 0;
        }
        throw e;
      }
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-033 / V5 — 100 courses retrait / assignation : jamais d'assigné non membre, retrait toujours 204")
  void removeVersusAssign() throws Exception {
    for (int run = 0; run < RUNS; run++) {
      BoardDatabase.loadReference();
      int card = 402 + (run % 2);
      List<Integer> results =
          ConcurrentRequests.together(
              List.of(
                  () ->
                      raw("DELETE", bruno(), "/api/boards/101/members/" + Fixture.CARLA, null)
                          .status(),
                  () -> assignCarlaTo(card)));
      assertThat(results.getFirst()).as("exécution %d", run).isEqualTo(204);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM taskora.card_assignee ca JOIN taskora.card c ON c.id = ca.card_id"
                      + " LEFT JOIN taskora.board_member m ON m.board_id = c.board_id AND m.user_id = ca.user_id"
                      + " WHERE c.board_id = 101 AND m.id IS NULL"))
          .as("exécution %d : assigné non membre (V5)", run)
          .isZero();
    }
  }
}
