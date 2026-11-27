package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.BoardRepositoryPort;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.DefaultBoardContent.DefaultLabel;
import com.taskora.board.domain.model.DefaultBoardContent.DefaultList;
import com.taskora.board.domain.model.NewBoard;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Écritures sur {@code board}, {@code board_list} et {@code label} (DML seulement, B-ADR-1) ; un
 * {@code UPDATE} ne touche que les colonnes modifiées.
 */
public class JdbcBoardRepository implements BoardRepositoryPort {
  private final JdbcClient jdbc;
  private final LegacyClock clock;

  public JdbcBoardRepository(JdbcClient jdbc, LegacyClock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public BoardId insert(NewBoard board) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql(
            "INSERT INTO board (title, description, background, created_at, owner_id, card_seq,"
                + " show_card_numbers) VALUES (?, ?, ?, ?, ?, 0, 1)")
        .param(board.title().value())
        .param(board.description())
        .param(board.background().value())
        .param(clock.toWall(board.createdAt()))
        .param(board.ownerId().value())
        .update(keys);
    Number key = keys.getKey();
    if (key == null) {
      throw new IllegalStateException("identifiant de tableau non renvoyé");
    }
    return new BoardId(key.intValue());
  }

  @Override
  public void insertDefaultContent(
      BoardId board, List<DefaultList> lists, List<DefaultLabel> labels) {
    for (DefaultList list : lists) {
      jdbc.sql("INSERT INTO board_list (title, position, color, board_id) VALUES (?, ?, NULL, ?)")
          .param(list.title())
          .param(list.position())
          .param(board.value())
          .update();
    }
    for (DefaultLabel label : labels) {
      jdbc.sql("INSERT INTO label (name, color, board_id) VALUES (?, ?, ?)")
          .param(label.name())
          .param(label.color())
          .param(board.value())
          .update();
    }
  }

  @Override
  public void update(BoardId board, BoardChanges changes) {
    // Colonnes choisies parmi des fragments constants ; toutes les valeurs sont liées.
    StringJoiner set = new StringJoiner(", ", "UPDATE board SET ", " WHERE id = :id");
    if (changes.title() != null) {
      set.add("title = :title");
    }
    if (changes.descriptionChanged()) {
      set.add("description = :description");
    }
    if (changes.background() != null) {
      set.add("background = :background");
    }
    if (changes.showCardNumbers() != null) {
      set.add("show_card_numbers = :showCardNumbers");
    }
    JdbcClient.StatementSpec spec = jdbc.sql(set.toString()).param("id", board.value());
    if (changes.title() != null) {
      spec = spec.param("title", changes.title().value());
    }
    if (changes.descriptionChanged()) {
      spec = spec.param("description", changes.description());
    }
    if (changes.background() != null) {
      spec = spec.param("background", changes.background().value());
    }
    if (changes.showCardNumbers() != null) {
      spec = spec.param("showCardNumbers", changes.showCardNumbers() ? 1 : 0);
    }
    spec.update();
  }

  @Override
  public void markTrashed(BoardId board, UserId by, Instant at) {
    jdbc.sql("UPDATE board SET deleted_at = ?, deleted_by_id = ? WHERE id = ?")
        .param(clock.toWall(at))
        .param(by.value())
        .param(board.value())
        .update();
  }

  @Override
  public void restore(BoardId board) {
    jdbc.sql("UPDATE board SET deleted_at = NULL, deleted_by_id = NULL WHERE id = ?")
        .param(board.value())
        .update();
  }

  @Override
  public String titleOf(BoardId board) {
    return jdbc.sql("SELECT title FROM board WHERE id = ?")
        .param(board.value())
        .query(String.class)
        .single();
  }
}
