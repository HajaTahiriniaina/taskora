package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.InvitationRepositoryPort;
import com.taskora.board.domain.exception.ConflictException;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Invitation;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Écritures sur {@code invitation} ; index unique {@code (board_id, email)} traduit en 409. */
public class JdbcInvitationRepository implements InvitationRepositoryPort {
  private final JdbcClient jdbc;
  private final LegacyClock clock;

  public JdbcInvitationRepository(JdbcClient jdbc, LegacyClock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void insert(BoardId board, String email, UserId invitedBy, Instant at) {
    try {
      jdbc.sql(
              "INSERT INTO invitation (email, created_at, board_id, invited_by_id)"
                  + " VALUES (?, ?, ?, ?)")
          .param(email)
          .param(clock.toWall(at))
          .param(board.value())
          .param(invitedBy.value())
          .update();
    } catch (DuplicateKeyException e) {
      throw new ConflictException(DomainMessages.ALREADY_INVITED);
    }
  }

  @Override
  public boolean deleteFromBoard(BoardId board, int invitationId) {
    return jdbc.sql("DELETE FROM invitation WHERE id = ? AND board_id = ?")
            .param(invitationId)
            .param(board.value())
            .update()
        > 0;
  }

  @Override
  public List<Invitation> lockByEmail(String email) {
    return jdbc.sql(
            "SELECT id, board_id, email, invited_by_id, created_at FROM invitation"
                + " WHERE email = ? ORDER BY id FOR UPDATE")
        .param(email)
        .query((rs, n) -> {
          int inviter = rs.getInt("invited_by_id");
          UserId invitedBy = rs.wasNull() ? null : new UserId(inviter);
          return new Invitation(
              rs.getInt("id"),
              new BoardId(rs.getInt("board_id")),
              SqlRows.text(rs, "email"),
              invitedBy,
              clock.instantOf(SqlRows.wall(rs, "created_at")));
        })
        .list();
  }

  @Override
  public void delete(int invitationId) {
    jdbc.sql("DELETE FROM invitation WHERE id = ?").param(invitationId).update();
  }
}
