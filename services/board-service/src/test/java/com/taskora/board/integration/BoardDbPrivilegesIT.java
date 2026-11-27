package com.taskora.board.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.BoardDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R1/R2, FA10, B-ADR-1 point 3 — le compte {@code board} créé par le VRAI script d'init livré
 * ({@code docker/mariadb/initdb.d/10-taskora-init.sh}) a exactement les droits de
 * board-migration.md §3 : DML sur 13 tables, SELECT par colonne sur {@code user} (jamais {@code
 * password}), SELECT sur {@code attachment} et {@code trello_import_file}, tous droits utiles sur
 * {@code board} sauf DROP ; aucun DDL sur {@code taskora} ; les cascades s'appliquent sans droit
 * sur les tables filles. JDBC pur, sans board-service.
 */
class BoardDbPrivilegesIT {
  private static final String GRANTEE = "'board'@'%'";
  private static final List<String> DML_TABLES =
      List.of(
          "board",
          "board_list",
          "board_member",
          "invitation",
          "card",
          "card_assignee",
          "card_label",
          "label",
          "custom_field",
          "custom_field_value",
          "checklist_item",
          "comment",
          "activity");

  @BeforeEach
  void reference() {
    BoardDatabase.loadReference();
  }

  @AfterAll
  static void close() {
    BoardDatabase.rootExecute(List.of("DROP TABLE IF EXISTS board.it_scratch"));
    BoardDatabase.closeRootPool();
  }

  private static Set<String> rows(String sql) {
    Set<String> out = new HashSet<>();
    for (Map<String, Object> r : BoardDatabase.query(sql)) {
      out.add(String.join(" ", r.values().stream().map(String::valueOf).toList()));
    }
    return out;
  }

  private static void asBoard(String sql) throws SQLException {
    try (Connection c = BoardDatabase.boardConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static int errorCode(String sql) {
    try {
      asBoard(sql);
      return 0;
    } catch (SQLException e) {
      return e.getErrorCode();
    }
  }

  @Test
  @DisplayName(
      "R2 — droits de table exacts sur taskora : 13 tables × DML + SELECT sur attachment et trello_import_file (54)")
  void tablePrivileges() {
    Set<String> expected = new HashSet<>();
    for (String t : DML_TABLES) {
      for (String p : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        expected.add(t + " " + p);
      }
    }
    expected.add("attachment SELECT");
    expected.add("trello_import_file SELECT");
    assertThat(
            rows(
                "SELECT TABLE_NAME, PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES"
                    + " WHERE GRANTEE = \""
                    + GRANTEE
                    + "\" AND TABLE_SCHEMA = 'taskora'"))
        .containsExactlyInAnyOrderElementsOf(expected)
        .hasSize(54);
  }

  @Test
  @DisplayName(
      "R2 / FA10 — SELECT par colonne sur user : id, email, name, avatar_color, created_at (jamais password)")
  void columnPrivileges() {
    assertThat(
            rows(
                "SELECT TABLE_NAME, COLUMN_NAME, PRIVILEGE_TYPE FROM information_schema.COLUMN_PRIVILEGES"
                    + " WHERE GRANTEE = \""
                    + GRANTEE
                    + "\""))
        .containsExactlyInAnyOrder(
            "user id SELECT",
            "user email SELECT",
            "user name SELECT",
            "user avatar_color SELECT",
            "user created_at SELECT");
  }

  @Test
  @DisplayName(
      "R1 — droits de schéma : tout sur board sauf DROP ; rien au niveau du schéma taskora ni global")
  void schemaPrivileges() {
    assertThat(
            rows(
                "SELECT TABLE_SCHEMA, PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES"
                    + " WHERE GRANTEE = \""
                    + GRANTEE
                    + "\""))
        .containsExactlyInAnyOrder(
            "board SELECT",
            "board INSERT",
            "board UPDATE",
            "board DELETE",
            "board CREATE",
            "board ALTER",
            "board INDEX",
            "board REFERENCES");
    assertThat(
            rows(
                "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = \""
                    + GRANTEE
                    + "\""))
        .containsExactly("USAGE");
  }

  @Test
  @DisplayName("R1 — sous le compte board : ALTER, CREATE, DROP, INDEX sur taskora refusés (1142)")
  void noDdlOnTaskora() {
    assertThat(errorCode("ALTER TABLE taskora.card ADD COLUMN it_x INT")).isEqualTo(1142);
    assertThat(errorCode("CREATE TABLE taskora.it_x (id INT)")).isEqualTo(1142);
    assertThat(errorCode("CREATE INDEX it_idx ON taskora.card (title)")).isEqualTo(1142);
    assertThat(errorCode("DROP TABLE taskora.comment")).isEqualTo(1142);
    assertThat(BoardDatabase.count("SELECT COUNT(*) FROM taskora.comment")).isEqualTo(1);
  }

  @Test
  @DisplayName("FA10 — SELECT password refusé (1143) ; SELECT des colonnes permises accepté")
  void passwordColumnRefused() throws SQLException {
    assertThat(errorCode("SELECT password FROM taskora.`user`")).isEqualTo(1143);
    assertThat(errorCode("SELECT * FROM taskora.`user`"))
        .as("SELECT * refusé (1142 : pas de droit de table)")
        .isIn(1142, 1143);
    asBoard("SELECT id, email, name, avatar_color, created_at FROM taskora.`user`");
  }

  @Test
  @DisplayName("R2 — aucune écriture sur notification, push_subscription, attachment (T1) ni user")
  void noWritesOutsideScope() {
    assertThat(
            errorCode(
                "INSERT INTO taskora.notification (type, message, is_read, created_at, recipient_id)"
                    + " VALUES ('x', 'x', 0, '2026-11-27 11:00:00', 1)"))
        .isEqualTo(1142);
    assertThat(errorCode("UPDATE taskora.`user` SET name = 'x' WHERE id = 1")).isEqualTo(1142);
    assertThat(errorCode("DELETE FROM taskora.attachment")).isEqualTo(1142);
    assertThat(
            errorCode(
                "INSERT INTO taskora.attachment (name, storage_key, mime_type, size, created_at, card_id)"
                    + " VALUES ('x', 'uploads/x', 'text/plain', 1, '2026-11-27 11:00:00', 401)"))
        .as("INSERT sur attachment seulement à T4 (BOARD_GRANT_COMMENT_FILES=on)")
        .isEqualTo(1142);
    assertThat(errorCode("DELETE FROM taskora.push_subscription")).isEqualTo(1142);
  }

  @Test
  @DisplayName(
      "FA10 — DELETE FROM board en cascade jusqu'à attachment et notification, sans droit sur ces tables")
  void cascades() throws SQLException {
    asBoard("DELETE FROM taskora.board WHERE id = 101");
    assertThat(BoardDatabase.count("SELECT COUNT(*) FROM taskora.attachment WHERE id = 901"))
        .isZero();
    assertThat(
            BoardDatabase.count("SELECT COUNT(*) FROM taskora.notification WHERE board_id = 101"))
        .isZero();
    assertThat(BoardDatabase.count("SELECT COUNT(*) FROM taskora.card WHERE board_id = 101"))
        .isZero();
    assertThat(
            BoardDatabase.count(
                "SELECT COUNT(*) FROM taskora.trello_import_file WHERE board_id = 101"))
        .isZero();
  }

  @Test
  @DisplayName(
      "FA10 — INSERT dans board_member (FK vers user) sans droit sur user ; collation de user.email")
  void insertMemberAndCollation() throws SQLException {
    asBoard(
        "INSERT INTO taskora.board_member (role, favorite, joined_at, board_id, user_id)"
            + " VALUES ('member', 0, '2026-11-27 11:00:00', 101, 6)");
    try (Connection c = BoardDatabase.boardConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT id FROM taskora.`user` WHERE email = 'ALICE@EXEMPLE.TEST  '")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "B6 — CREATE TABLE accepté dans board, mais DROP refusé (1142) : pas de Flyway clean possible")
  void noDropInBoardSchema() throws SQLException {
    BoardDatabase.rootExecute(List.of("DROP TABLE IF EXISTS board.it_scratch"));
    asBoard("CREATE TABLE board.it_scratch (id INT)");
    assertThat(errorCode("DROP TABLE board.it_scratch")).isEqualTo(1142);
  }
}
