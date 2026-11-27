package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.MembershipRepositoryPort;
import com.taskora.board.domain.exception.ConflictException;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Écritures sur {@code board_member} et {@code card_assignee} (B-ADR-4 point 3) : adhésion visée
 * verrouillée ({@code FOR UPDATE}), index unique {@code (board_id, user_id)} traduit en 409,
 * changement de rôle conditionnel {@code role <> 'owner'}.
 */
public class JdbcMembershipRepository implements MembershipRepositoryPort {
  private final JdbcClient jdbc;
  private final LegacyClock clock;

  public JdbcMembershipRepository(JdbcClient jdbc, LegacyClock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void insert(Membership membership) {
    try {
      jdbc.sql(
              "INSERT INTO board_member (role, favorite, joined_at, board_id, user_id)"
                  + " VALUES (?, ?, ?, ?, ?)")
          .param(membership.role().value())
          .param(membership.favorite() ? 1 : 0)
          .param(clock.toWall(membership.joinedAt()))
          .param(membership.boardId().value())
          .param(membership.userId().value())
          .update();
    } catch (DuplicateKeyException e) {
      throw new ConflictException(DomainMessages.ALREADY_MEMBER);
    }
  }

  @Override
  public Optional<Membership> lock(BoardId board, UserId user) {
    return jdbc.sql(
            "SELECT role, favorite, joined_at FROM board_member"
                + " WHERE board_id = ? AND user_id = ? FOR UPDATE")
        .param(board.value())
        .param(user.value())
        .query((rs, n) ->
            new Membership(
                board,
                user,
                Role.fromStored(SqlRows.text(rs, "role")),
                rs.getBoolean("favorite"),
                clock.instantOf(SqlRows.wall(rs, "joined_at"))))
        .optional();
  }

  @Override
  public boolean updateRoleUnlessOwner(BoardId board, UserId user, Role role) {
    return jdbc.sql(
                "UPDATE board_member SET role = ?"
                    + " WHERE board_id = ? AND user_id = ? AND role <> 'owner'")
            .param(role.value())
            .param(board.value())
            .param(user.value())
            .update()
        > 0;
  }

  @Override
  public void removeWithAssignments(BoardId board, UserId user) {
    jdbc.sql(
            "DELETE ca FROM card_assignee ca JOIN card c ON c.id = ca.card_id"
                + " WHERE c.board_id = ? AND ca.user_id = ?")
        .param(board.value())
        .param(user.value())
        .update();
    jdbc.sql("DELETE FROM board_member WHERE board_id = ? AND user_id = ?")
        .param(board.value())
        .param(user.value())
        .update();
  }

  @Override
  public boolean toggleFavorite(BoardId board, UserId user) {
    jdbc.sql("UPDATE board_member SET favorite = 1 - favorite WHERE board_id = ? AND user_id = ?")
        .param(board.value())
        .param(user.value())
        .update();
    return jdbc.sql("SELECT favorite FROM board_member WHERE board_id = ? AND user_id = ?")
        .param(board.value())
        .param(user.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<UserId> memberIds(BoardId board) {
    return jdbc.sql("SELECT user_id FROM board_member WHERE board_id = ? ORDER BY id")
        .param(board.value())
        .query((rs, n) -> new UserId(rs.getInt("user_id")))
        .list();
  }
}
