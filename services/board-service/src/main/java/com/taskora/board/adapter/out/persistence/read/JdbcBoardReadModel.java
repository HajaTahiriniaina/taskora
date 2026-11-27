package com.taskora.board.adapter.out.persistence.read;

import com.taskora.board.adapter.out.persistence.LegacyClock;
import com.taskora.board.adapter.out.persistence.SqlRows;
import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.CustomFieldView;
import com.taskora.board.application.port.in.InvitationView;
import com.taskora.board.application.port.in.LabelView;
import com.taskora.board.application.port.in.ListView;
import com.taskora.board.application.port.in.MemberView;
import com.taskora.board.application.port.in.TrashedBoardView;
import com.taskora.board.application.port.out.BoardReadModelPort;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Modèles de lecture (B-ADR-8) : nombre de requêtes constant quel que soit le volume (jointures et
 * sous-requêtes corrélées, jamais une requête par tableau ou par liste). Ordres déterministes (R8).
 */
public class JdbcBoardReadModel implements BoardReadModelPort {
  /** Listes ouvertes : ni archivées ni dans la corbeille. */
  private static final String OPEN_LIST = "l.archived_at IS NULL AND l.deleted_at IS NULL";

  /** Cartes ouvertes : ni archivées ni dans la corbeille. */
  private static final String OPEN_CARD = "c.archived_at IS NULL AND c.deleted_at IS NULL";

  private static final String MEMBER_COLUMNS =
      "SELECT m.board_id, u.id, u.name, u.email, u.avatar_color, m.role, m.joined_at"
          + " FROM board_member m JOIN `user` u ON u.id = m.user_id";

  private static final String BOARD_COLUMNS =
      "SELECT b.id, b.title, b.description, b.background, b.show_card_numbers, b.created_at,"
          + " m.favorite, m.role";

  private final JdbcClient jdbc;
  private final LegacyClock clock;

  public JdbcBoardReadModel(JdbcClient jdbc, LegacyClock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public List<BoardSummaryView> boardsOf(UserId viewer) {
    List<BoardRow> rows =
        jdbc.sql(
                BOARD_COLUMNS
                    + ", (SELECT COUNT(*) FROM board_list l WHERE l.board_id = b.id AND "
                    + OPEN_LIST
                    + ") AS list_count, (SELECT COUNT(*) FROM card c"
                    + " JOIN board_list l ON l.id = c.list_id WHERE l.board_id = b.id AND "
                    + OPEN_LIST
                    + " AND "
                    + OPEN_CARD
                    + ") AS card_count FROM board b"
                    + " JOIN board_member m ON m.board_id = b.id AND m.user_id = ?"
                    + " WHERE b.deleted_at IS NULL ORDER BY b.created_at DESC, b.id ASC")
            .param(viewer.value())
            .query((rs, n) -> boardRow(rs, rs.getInt("list_count"), rs.getInt("card_count")))
            .list();
    Map<Integer, List<MemberView>> members =
        groupMembers(
            jdbc.sql(
                    MEMBER_COLUMNS
                        + " JOIN board_member mine ON mine.board_id = m.board_id"
                        + " AND mine.user_id = ? JOIN board b ON b.id = m.board_id"
                        + " WHERE b.deleted_at IS NULL ORDER BY m.board_id, m.user_id")
                .param(viewer.value())
                .query((rs, n) -> memberRow(rs))
                .list());
    List<BoardSummaryView> out = new ArrayList<>(rows.size());
    for (BoardRow row : rows) {
      out.add(row.summary(members.getOrDefault(row.id(), List.of())));
    }
    return out;
  }

  @Override
  public BoardView board(BoardId board, UserId viewer) {
    BoardRow row = singleBoard(board, viewer);
    List<MemberView> members = membersOf(board);
    List<ListView> lists =
        jdbc.sql(
                "SELECT l.id, l.title, l.position, l.color, l.archived_at,"
                    + " (SELECT COUNT(*) FROM card c WHERE c.list_id = l.id AND "
                    + OPEN_CARD
                    + ") AS card_count FROM board_list l WHERE l.board_id = ? AND "
                    + OPEN_LIST
                    + " ORDER BY l.position, l.id")
            .param(board.value())
            .query((rs, n) ->
                new ListView(
                    rs.getInt("id"),
                    SqlRows.text(rs, "title"),
                    rs.getInt("position"),
                    rs.getString("color"),
                    date(rs, "archived_at"),
                    rs.getInt("card_count")))
            .list();
    List<LabelView> labels =
        jdbc.sql("SELECT id, name, color FROM label WHERE board_id = ? ORDER BY id")
            .param(board.value())
            .query((rs, n) ->
                new LabelView(rs.getInt("id"), SqlRows.text(rs, "name"), SqlRows.text(rs, "color")))
            .list();
    List<CustomFieldView> fields =
        jdbc.sql(
                "SELECT id, name, type, options, show_on_card, position FROM custom_field"
                    + " WHERE board_id = ? ORDER BY position, id")
            .param(board.value())
            .query((rs, n) ->
                new CustomFieldView(
                    rs.getInt("id"),
                    SqlRows.text(rs, "name"),
                    SqlRows.text(rs, "type"),
                    SqlRows.text(rs, "options"),
                    rs.getBoolean("show_on_card"),
                    rs.getInt("position")))
            .list();
    List<InvitationView> invitations =
        jdbc.sql("SELECT id, email, created_at FROM invitation WHERE board_id = ? ORDER BY id")
            .param(board.value())
            .query((rs, n) ->
                new InvitationView(
                    rs.getInt("id"),
                    SqlRows.text(rs, "email"),
                    clock.fromWall(SqlRows.wall(rs, "created_at"))))
            .list();
    int listCount = lists.size();
    int cardCount = lists.stream().mapToInt(ListView::cardCount).sum();
    BoardRow counted =
        new BoardRow(
            row.id(),
            row.title(),
            row.description(),
            row.background(),
            row.showCardNumbers(),
            row.createdAt(),
            row.favorite(),
            row.role(),
            listCount,
            cardCount,
            row.trelloFiles());
    return new BoardView(
        counted.summary(members), row.trelloFiles(), lists, labels, fields, invitations);
  }

  @Override
  public BoardSummaryView summaryWithoutCounters(BoardId board, UserId viewer) {
    return singleBoard(board, viewer).summary(membersOf(board));
  }

  @Override
  public MemberView member(BoardId board, UserId user) {
    return jdbc.sql(MEMBER_COLUMNS + " WHERE m.board_id = ? AND m.user_id = ?")
        .param(board.value())
        .param(user.value())
        .query((rs, n) -> memberRow(rs).member())
        .single();
  }

  @Override
  public List<TrashedBoardView> trashedBoardsOwnedBy(UserId owner) {
    return jdbc.sql(
            "SELECT b.id, b.title, b.background, b.deleted_at, u.name AS deleted_by FROM board b"
                + " JOIN board_member m ON m.board_id = b.id AND m.user_id = ? AND m.role = 'owner'"
                + " LEFT JOIN `user` u ON u.id = b.deleted_by_id"
                + " WHERE b.deleted_at IS NOT NULL ORDER BY b.deleted_at DESC, b.id ASC")
        .param(owner.value())
        .query((rs, n) -> {
          LocalDateTime deletedAt = SqlRows.wall(rs, "deleted_at");
          return new TrashedBoardView(
              rs.getInt("id"),
              SqlRows.text(rs, "title"),
              SqlRows.text(rs, "background"),
              clock.fromWall(deletedAt),
              rs.getString("deleted_by"),
              clock.purgeAt(deletedAt));
        })
        .list();
  }

  private BoardRow singleBoard(BoardId board, UserId viewer) {
    return jdbc.sql(
            BOARD_COLUMNS
                + ", (SELECT COUNT(*) FROM trello_import_file t WHERE t.board_id = b.id"
                + " AND t.status <> 'done') AS trello_files"
                + " FROM board b LEFT JOIN board_member m ON m.board_id = b.id AND m.user_id = ?"
                + " WHERE b.id = ?")
        .param(viewer.value())
        .param(board.value())
        .query((rs, n) -> boardRow(rs, 0, 0, rs.getInt("trello_files")))
        .single();
  }

  private List<MemberView> membersOf(BoardId board) {
    return jdbc.sql(MEMBER_COLUMNS + " WHERE m.board_id = ? ORDER BY m.user_id")
        .param(board.value())
        .query((rs, n) -> memberRow(rs).member())
        .list();
  }

  private BoardRow boardRow(ResultSet rs, int listCount, int cardCount) throws SQLException {
    return boardRow(rs, listCount, cardCount, 0);
  }

  private BoardRow boardRow(ResultSet rs, int listCount, int cardCount, int trelloFiles)
      throws SQLException {
    String role = rs.getString("role");
    return new BoardRow(
        rs.getInt("id"),
        SqlRows.text(rs, "title"),
        rs.getString("description"),
        SqlRows.text(rs, "background"),
        rs.getBoolean("show_card_numbers"),
        clock.fromWall(SqlRows.wall(rs, "created_at")),
        rs.getBoolean("favorite"),
        role == null ? null : Role.fromStored(role),
        listCount,
        cardCount,
        trelloFiles);
  }

  private MemberRow memberRow(ResultSet rs) throws SQLException {
    return new MemberRow(
        rs.getInt("board_id"),
        new MemberView(
            rs.getInt("id"),
            SqlRows.text(rs, "name"),
            SqlRows.text(rs, "email"),
            SqlRows.text(rs, "avatar_color"),
            Role.fromStored(SqlRows.text(rs, "role")),
            clock.fromWall(SqlRows.wall(rs, "joined_at"))));
  }

  private @Nullable OffsetDateTime date(ResultSet rs, String column) throws SQLException {
    LocalDateTime wall = rs.getObject(column, LocalDateTime.class);
    return wall == null ? null : clock.fromWall(wall);
  }

  private static Map<Integer, List<MemberView>> groupMembers(List<MemberRow> rows) {
    Map<Integer, List<MemberView>> out = new HashMap<>();
    for (MemberRow row : rows) {
      out.computeIfAbsent(row.boardId(), k -> new ArrayList<>()).add(row.member());
    }
    return out;
  }

  private record MemberRow(int boardId, MemberView member) {}

  private record BoardRow(
      int id,
      String title,
      @Nullable String description,
      String background,
      boolean showCardNumbers,
      OffsetDateTime createdAt,
      boolean favorite,
      @Nullable Role role,
      int listCount,
      int cardCount,
      int trelloFiles) {

    BoardSummaryView summary(List<MemberView> members) {
      return new BoardSummaryView(
          id,
          title,
          description,
          background,
          showCardNumbers,
          favorite,
          role,
          members,
          listCount,
          cardCount,
          createdAt);
    }
  }
}
