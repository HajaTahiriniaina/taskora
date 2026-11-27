package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.BoardAccessQueryPort;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.policy.BoardAccessView;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Données d'accès en une requête (B-ADR-3 point 1), identique pour un non-membre et un tableau
 * inexistant (même nombre de requêtes, point 5).
 */
public class JdbcBoardAccessQuery implements BoardAccessQueryPort {
  private final JdbcClient jdbc;
  private final LegacyClock clock;

  public JdbcBoardAccessQuery(JdbcClient jdbc, LegacyClock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public BoardAccessView load(BoardId board, UserId caller) {
    return jdbc.sql(
            "SELECT b.id, b.deleted_at, m.role FROM board b"
                + " LEFT JOIN board_member m ON m.board_id = b.id AND m.user_id = ?"
                + " WHERE b.id = ?")
        .param(caller.value())
        .param(board.value())
        .query((rs, n) -> {
          LocalDateTime deletedAt = rs.getObject("deleted_at", LocalDateTime.class);
          String role = rs.getString("role");
          return new BoardAccessView(
              board,
              true,
              deletedAt == null ? null : clock.instantOf(deletedAt),
              role == null ? null : Role.fromStored(role));
        })
        .optional()
        .orElseGet(() -> BoardAccessView.missing(board));
  }
}
