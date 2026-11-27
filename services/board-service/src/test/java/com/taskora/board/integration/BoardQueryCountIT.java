package com.taskora.board.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.SqlCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * SC-BOARD-029, SC-BOARD-002 / 003 (B-ADR-8) — nombre de requêtes SQL constant et borné, quel que
 * soit le volume : GET /api/boards ≤ 4 (+1 résolution du jeton), GET /api/boards/{id} ≤ 9 (+1) ;
 * identique pour 1 tableau et 200 tableaux / 300 listes. Mesure déterministe (datasource-proxy),
 * sans mesure de temps (U5). Garde aussi B-ADR-1 : un PATCH ne réécrit que les colonnes présentes.
 */
@Import(SqlCounter.class)
class BoardQueryCountIT extends ApiTestBase {
  private static final int TOKEN_RESOLUTION = 1;

  private int queriesFor(Runnable request) {
    SqlCounter.reset();
    request.run();
    return SqlCounter.count();
  }

  /** Eve : 1 tableau (104) ; puis 199 tableaux de plus et 300 listes avec cartes. */
  private static void growEveTo200Boards() {
    List<String> sql = new ArrayList<>();
    StringBuilder boards = new StringBuilder();
    StringBuilder members = new StringBuilder();
    for (int i = 0; i < 199; i++) {
      int id = 2000 + i;
      boards
          .append(boards.isEmpty() ? "" : ",")
          .append("(")
          .append(id)
          .append(", 'Volume ")
          .append(i)
          .append("', 'aurora', '2026-11-01 12:00:00', 7, 0, 1)");
      members
          .append(members.isEmpty() ? "" : ",")
          .append("('owner', 0, '2026-11-01 12:00:00', ")
          .append(id)
          .append(", 7), ('member', 0, '2026-11-02 12:00:00', ")
          .append(id)
          .append(", 2)");
    }
    sql.add(
        "INSERT INTO taskora.board (id, title, background, created_at, owner_id, card_seq, show_card_numbers) VALUES "
            + boards);
    sql.add(
        "INSERT INTO taskora.board_member (role, favorite, joined_at, board_id, user_id) VALUES "
            + members);
    StringBuilder lists = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      lists
          .append(lists.isEmpty() ? "" : ",")
          .append("(")
          .append(5000 + i)
          .append(", 'Liste ")
          .append(i)
          .append("', ")
          .append(i)
          .append(", ")
          .append(i < 150 ? Integer.toString(104) : Integer.toString(2000 + (i % 199)))
          .append(")");
    }
    sql.add("INSERT INTO taskora.board_list (id, title, position, board_id) VALUES " + lists);
    StringBuilder cards = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      int list = 5000 + i;
      int board = i < 150 ? 104 : 2000 + (i % 199);
      cards
          .append(cards.isEmpty() ? "" : ",")
          .append("('Carte ")
          .append(i)
          .append("', 0, 0, 'none', '2026-11-03 12:00:00', ")
          .append(list)
          .append(", ")
          .append(10_000 + i)
          .append(", ")
          .append(board)
          .append(")");
    }
    sql.add(
        "INSERT INTO taskora.card (title, position, completed, priority, created_at, list_id, number, board_id) VALUES "
            + cards);
    sql.add("UPDATE taskora.board SET card_seq = 20000 WHERE owner_id = 7");
    sql.add(
        "INSERT INTO taskora.label (name, color, board_id) SELECT CONCAT('L', id), '#000000', id FROM taskora.board_list WHERE board_id = 104");
    sql.add(
        "INSERT INTO taskora.invitation (email, created_at, board_id, invited_by_id) SELECT CONCAT('inv', id, '@exemple.test'),"
            + " '2026-11-04 12:00:00', 104, 7 FROM taskora.board_list WHERE board_id = 104");
    BoardDatabase.rootExecute(sql);
  }

  @Test
  @DisplayName(
      "SC-BOARD-029 — GET /api/boards : même nombre de requêtes pour 1 et 200 tableaux, ≤ 4 (+1 jeton)")
  void homeConstant() {
    int small = queriesFor(() -> assertStatus(get(eve(), "/api/boards"), 200));
    growEveTo200Boards();
    int large = queriesFor(() -> assertThat(json(get(eve(), "/api/boards"))).hasSize(200));
    assertThat(large).as("indépendant du volume").isEqualTo(small);
    assertThat(large).isLessThanOrEqualTo(4 + TOKEN_RESOLUTION);
  }

  @Test
  @DisplayName(
      "SC-BOARD-029 — GET /api/boards/{id} : même nombre pour 4 et 150 listes (300 au total), ≤ 9 (+1 jeton)")
  void shellConstant() {
    int small = queriesFor(() -> assertStatus(get(eve(), "/api/boards/104"), 200));
    growEveTo200Boards();
    int large =
        queriesFor(() -> assertThat(json(get(eve(), "/api/boards/104")).get("lists")).hasSize(151));
    assertThat(large).as("indépendant du volume").isEqualTo(small);
    assertThat(large).isLessThanOrEqualTo(9 + TOKEN_RESOLUTION);
  }

  @Test
  @DisplayName(
      "SC-BOARD-021 / B-ADR-3 point 5 — non-membre et inexistant : même nombre de requêtes (pas d'oracle temporel grossier)")
  void sameQueryCountForNotFound() {
    int nonMember = queriesFor(() -> assertStatus(get(nadia(), "/api/boards/101"), 404));
    int missing = queriesFor(() -> assertStatus(get(nadia(), "/api/boards/99999"), 404));
    assertThat(nonMember).isEqualTo(missing);
  }

  @Test
  @DisplayName(
      "B-ADR-1 — PATCH du titre seul : l'UPDATE de board ne touche que title (aucune autre colonne réécrite)")
  void partialUpdate() {
    SqlCounter.reset();
    assertStatus(patch(alice(), "/api/boards/101", "{\"title\":\"Seul le titre\"}"), 200);
    List<String> updates =
        SqlCounter.statements().stream()
            .map(s -> s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
            .filter(s -> s.startsWith("update") && s.contains("board ") && !s.contains("board_"))
            .toList();
    assertThat(updates).hasSize(1);
    assertThat(updates.getFirst())
        .contains("title")
        .doesNotContain("description")
        .doesNotContain("background")
        .doesNotContain("show_card_numbers")
        .doesNotContain("card_seq")
        .doesNotContain("owner_id")
        .doesNotContain("deleted_at");
  }
}
