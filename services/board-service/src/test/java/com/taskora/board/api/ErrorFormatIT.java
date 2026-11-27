package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SC-BOARD-024, U13 (E16 étendu), E12 — format des erreurs, corps vide = {}, corps non objet → 400
 * JSON invalide. sans écriture (et 404 d'abord pour un non-membre), identifiants non numériques →
 * 404 Ressource introuvable., 500 sans trace, trim PHP et troncature en points de code.
 */
class ErrorFormatIT extends ApiTestBase {
  private static final List<String> NOT_OBJECT =
      List.of("[]", "[1]", "\"x\"", "42", "true", "null");

  /** Routes à corps (U13) : méthode, chemin. */
  private static final List<String[]> BODY_ROUTES =
      List.of(
          new String[] {"POST", "/api/boards"},
          new String[] {"PATCH", "/api/boards/101"},
          new String[] {"POST", "/api/boards/101/members"},
          new String[] {"PATCH", "/api/boards/101/members/" + Fixture.CARLA});

  static Stream<Arguments> notObjectBodies() {
    List<Arguments> out = new ArrayList<>();
    for (String[] route : BODY_ROUTES) {
      for (String body : NOT_OBJECT) {
        out.add(Arguments.of(route[0], route[1], body));
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0} {1} ← {2}")
  @MethodSource("notObjectBodies")
  @DisplayName("U13 — corps JSON valide non objet → 400 JSON invalide. sans écriture")
  void notAnObject(String method, String path, String body) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(send(method, alice(), path, body), 400, "JSON invalide.");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest(name = "{0} {1} ← {2}")
  @MethodSource("notObjectBodies")
  @DisplayName(
      "U13 / SC-BOARD-021 — non-membre + corps non objet → 404 d'abord (jamais JSON invalide.)")
  void nonMemberSeesNotFoundFirst(String method, String path, String body) {
    if ("/api/boards".equals(path)) {
      return; // création : aucune ressource visée
    }
    assertError(send(method, nadia(), path, body), 404, "Tableau introuvable.");
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("bodyRoutes")
  @DisplayName("SC-BOARD-024 — corps non JSON → 400 JSON invalide.")
  void notJson(String method, String path) {
    assertError(send(method, alice(), path, "{\"title\": "), 400, "JSON invalide.");
    assertError(send(method, alice(), path, "texte"), 400, "JSON invalide.");
  }

  static Stream<Arguments> bodyRoutes() {
    return BODY_ROUTES.stream().map(r -> Arguments.of(r[0], r[1]));
  }

  @Test
  @DisplayName("SC-BOARD-024 — POST /favorite sans corps (comme le front) → traité comme {} : 200")
  void emptyBodyOnFavorite() {
    Response r = post(carla(), "/api/boards/101/favorite", null);
    assertStatus(r, 200);
    assertThat(json(r).get("favorite").asBoolean()).isFalse();
    Response withEmpty = post(carla(), "/api/boards/101/favorite", "");
    assertStatus(withEmpty, 200);
    assertThat(json(withEmpty).get("favorite").asBoolean()).isTrue();
  }

  @Test
  @DisplayName(
      "SC-BOARD-024 — corps vide = {} : POST /boards → titre obligatoire ; PATCH → rien ne change ; POST /members → e-mail obligatoire")
  void emptyBodyIsEmptyObject() {
    assertError(post(alice(), "/api/boards", ""), 400, "Le champ \"title\" est obligatoire.");
    Response p = patch(alice(), "/api/boards/101", "");
    assertStatus(p, 200);
    assertThat(json(p).get("title").asString()).isEqualTo(Fixture.LAUNCH_TITLE);
    assertError(
        post(alice(), "/api/boards/101/members", ""), 400, "Le champ \"email\" est obligatoire.");
  }

  @Test
  @DisplayName("SC-BOARD-024 — POST /favorite : un corps présent n'est ni analysé ni contrôlé")
  void favoriteBodyIgnored() {
    assertStatus(post(carla(), "/api/boards/101/favorite", "pas du json"), 200);
    assertStatus(post(carla(), "/api/boards/101/favorite", "[]"), 200);
  }

  @ParameterizedTest(name = "id = {0}")
  @ValueSource(strings = {"abc", "0", "-1", "99999999999", "1.5", "1e3", "%20"})
  @DisplayName("E12 — identifiant de tableau non entier positif → 404 Ressource introuvable.")
  void badBoardId(String id) {
    assertError(get(alice(), "/api/boards/" + id), 404, "Ressource introuvable.");
    assertError(
        patch(alice(), "/api/boards/" + id, "{\"title\":\"x\"}"), 404, "Ressource introuvable.");
    assertError(delete(alice(), "/api/boards/" + id), 404, "Ressource introuvable.");
    assertError(
        post(alice(), "/api/boards/" + id + "/favorite", null), 404, "Ressource introuvable.");
    assertError(
        post(alice(), "/api/trash/boards/" + id + "/restore", null), 404, "Ressource introuvable.");
  }

  @ParameterizedTest(name = "userId/invId = {0}")
  @ValueSource(strings = {"abc", "0", "-1", "99999999999"})
  @DisplayName("E12 — userId et invId non entiers positifs → 404 Ressource introuvable.")
  void badChildIds(String id) {
    assertError(
        patch(alice(), "/api/boards/101/members/" + id, "{\"role\":\"admin\"}"),
        404,
        "Ressource introuvable.");
    assertError(delete(alice(), "/api/boards/101/members/" + id), 404, "Ressource introuvable.");
    assertError(
        delete(alice(), "/api/boards/101/invitations/" + id), 404, "Ressource introuvable.");
  }

  @Test
  @DisplayName("E12 — sans jeton valide, un identifiant non numérique reçoit d'abord 401")
  void authBeforeE12() {
    assertAuthError(get(null, "/api/boards/abc"), "JWT Token not found");
  }

  @Test
  @DisplayName(
      "SC-BOARD-024 — erreur serveur simulée → 500 {\"error\":\"Erreur interne du serveur.\"} sans trace")
  void serverError() {
    BoardDatabase.rootExecute(
        List.of(
            "CREATE TRIGGER taskora.it_fail_favorite BEFORE UPDATE ON taskora.board_member"
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'panne simulée'"));
    try {
      Response r = post(carla(), "/api/boards/101/favorite", null);
      assertError(r, 500, "Erreur interne du serveur.");
      assertThat(r.asString())
          .doesNotContain("Exception")
          .doesNotContain("panne")
          .doesNotContain("SQL")
          .doesNotContain("at com.");
    } finally {
      BoardDatabase.rootExecute(List.of("DROP TRIGGER IF EXISTS taskora.it_fail_favorite"));
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-024 — titre : trim PHP (\\0, \\x0B compris), troncature à 160 points de code (emoji)")
  void trimAndTruncate() {
    Response r =
        post(alice(), "/api/boards", "{\"title\":\" \\t\\n\\r\\u0000\\u000bSprint\\u0000 \"}");
    assertStatus(r, 201);
    assertThat(json(r).get("title").asString()).isEqualTo("Sprint");
    String emoji = "🚀".repeat(170);
    Response long1 = post(alice(), "/api/boards", "{\"title\":\"" + emoji + "\"}");
    assertStatus(long1, 201);
    assertThat(json(long1).get("title").asString()).isEqualTo("🚀".repeat(160));
    int id = json(long1).get("id").asInt();
    assertThat(scalar("SELECT title FROM taskora.board WHERE id = ?", id))
        .isEqualTo("🚀".repeat(160));
  }

  @Test
  @DisplayName(
      "SC-BOARD-024 — vide après trim → 400 Le champ \"title\" est obligatoire. ; création 201, suppression 204 sans corps")
  void statuses() {
    assertError(
        post(alice(), "/api/boards", "{\"title\":\" \\t \"}"),
        400,
        "Le champ \"title\" est obligatoire.");
    Response created = post(alice(), "/api/boards", "{\"title\":\"À supprimer\"}");
    assertStatus(created, 201);
    Response deleted = delete(alice(), "/api/boards/" + json(created).get("id").asInt());
    assertStatus(deleted, 204);
    assertThat(deleted.asString()).isEmpty();
  }
}
