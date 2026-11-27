package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-002, SC-BOARD-008, SC-BOARD-029 (ordres) — GET /api/boards. */
class ListMyBoardsIT extends ApiTestBase {

  private static List<Integer> ids(JsonNode boards) {
    List<Integer> out = new ArrayList<>();
    boards.forEach(b -> out.add(b.get("id").asInt()));
    return out;
  }

  private static JsonNode board(JsonNode boards, int id) {
    for (JsonNode b : boards) {
      if (b.get("id").asInt() == id) {
        return b;
      }
    }
    throw new AssertionError("tableau " + id + " absent");
  }

  @Test
  @DisplayName(
      "SC-BOARD-002 / 029 — tableaux ouverts de l'appelant, created_at DESC puis id DESC (égalité 101/102), sans corbeille ni tableaux d'autrui")
  void listAndOrder() {
    Response r = get(alice(), "/api/boards");
    assertStatus(r, 200);
    assertThat(ids(json(r))).containsExactly(Fixture.IMPORTED, Fixture.LAUNCH);
  }

  @Test
  @DisplayName(
      "SC-BOARD-002 — compteurs : listes ouvertes et cartes ouvertes de ces listes (archivées et corbeille exclues)")
  void counters() {
    JsonNode launch = board(json(get(alice(), "/api/boards")), Fixture.LAUNCH);
    assertThat(launch.get("listCount").asInt()).isEqualTo(3);
    assertThat(launch.get("cardCount").asInt()).isEqualTo(5);
    JsonNode imported = board(json(get(alice(), "/api/boards")), Fixture.IMPORTED);
    assertThat(imported.get("listCount").asInt()).isEqualTo(1);
    assertThat(imported.get("cardCount").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-002 — résumé complet : rôle et favori de l'appelant, membres par id d'adhésion, dates ATOM")
  void summary() {
    JsonNode launch = board(json(get(carla(), "/api/boards")), Fixture.LAUNCH);
    assertThat(launch.get("title").asString()).isEqualTo(Fixture.LAUNCH_TITLE);
    assertThat(launch.get("description").asString()).isEqualTo("Plan de lancement");
    assertThat(launch.get("background").asString()).isEqualTo("ocean");
    assertThat(launch.get("showCardNumbers").asBoolean()).isTrue();
    assertThat(launch.get("role").asString()).isEqualTo("member");
    assertThat(launch.get("favorite").asBoolean()).isTrue();
    assertAtom(launch.get("createdAt"), "2026-07-14T09:15:00+02:00");
    List<String> members = new ArrayList<>();
    launch
        .get("members")
        .forEach(m -> members.add(m.get("id").asInt() + ":" + m.get("role").asString()));
    assertThat(members).containsExactly("1:owner", "2:admin", "3:member", "4:member");
    JsonNode bruno = launch.get("members").get(1);
    assertThat(bruno.get("name").asString()).isEqualTo("Bruno Admin");
    assertThat(bruno.get("email").asString()).isEqualTo(Fixture.BRUNO_EMAIL);
    assertThat(bruno.get("avatarColor").asString()).isEqualTo("#ef4444");
    assertAtom(bruno.get("joinedAt"), "2026-07-15T10:00:00+02:00");
    assertAtom(launch.get("members").get(3).get("joinedAt"), "2026-11-22T09:15:00+01:00");
  }

  @Test
  @DisplayName("SC-BOARD-008 — favori propre à chaque membre")
  void favoritePerMember() {
    assertThat(board(json(get(alice(), "/api/boards")), Fixture.LAUNCH).get("favorite").asBoolean())
        .isFalse();
    assertThat(
            board(json(get(alice(), "/api/boards")), Fixture.IMPORTED).get("favorite").asBoolean())
        .isTrue();
    assertThat(
            board(json(get(carla(), "/api/boards")), Fixture.IMPORTED).get("favorite").asBoolean())
        .isFalse();
  }

  @Test
  @DisplayName("SC-BOARD-008 — bascule du favori : true puis false, celui de Bob jamais modifié")
  void toggleFavorite() {
    Response first = post(alice(), "/api/boards/101/favorite", null);
    assertStatus(first, 200);
    assertThat(json(first).toString()).isEqualTo("{\"favorite\":true}");
    Response second = post(alice(), "/api/boards/101/favorite", null);
    assertThat(json(second).toString()).isEqualTo("{\"favorite\":false}");
    assertThat(
            count(
                "SELECT favorite FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                Fixture.CARLA))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT favorite FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                Fixture.BRUNO))
        .isZero();
  }

  @Test
  @DisplayName("SC-BOARD-002 — 0 tableau → [] ; tableau mis à la corbeille → absent pour tous")
  void emptyAndTrash() {
    assertThat(json(get(nadia(), "/api/boards"))).isEmpty();
    BoardDatabase.rootExecute(
        "UPDATE taskora.board SET deleted_at = '2026-11-27 10:00:00', deleted_by_id = 1 WHERE id = ?",
        Fixture.LAUNCH);
    assertThat(ids(json(get(carla(), "/api/boards")))).containsExactly(Fixture.IMPORTED);
    assertThat(ids(json(get(bruno(), "/api/boards")))).isEmpty();
  }

  @Test
  @DisplayName(
      "SC-BOARD-023 — aucun cache : un tableau écrit par Symfony (SQL direct) apparaît à la requête suivante")
  void noCache() {
    assertThat(ids(json(get(eve(), "/api/boards")))).containsExactly(Fixture.EVE_BOARD);
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.board (id, title, background, created_at, owner_id, card_seq, show_card_numbers)"
            + " VALUES (150, 'Importé par Symfony', 'aurora', '2026-11-27 11:30:00', 7, 0, 1)");
    BoardDatabase.rootExecute(
        "INSERT INTO taskora.board_member (role, favorite, joined_at, board_id, user_id)"
            + " VALUES ('owner', 0, '2026-11-27 11:30:00', 150, 7)");
    assertThat(ids(json(get(eve(), "/api/boards")))).containsExactly(150, Fixture.EVE_BOARD);
  }
}
