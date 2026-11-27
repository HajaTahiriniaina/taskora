package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-005, SC-CARD-043 (E22, E23), FA11 — PATCH /api/boards/{id} (tout membre, E4). */
class UpdateBoardIT extends ApiTestBase {

  private static Map<String, Object> row() {
    return query(
            "SELECT title, description, background, show_card_numbers FROM taskora.board WHERE id = 101")
        .getFirst();
  }

  @Test
  @DisplayName(
      "SC-BOARD-005 — simple membre : titre, description \"\" → null, fond, numéros ; réponse sans compteurs (FA11)")
  void nominal() {
    Response r =
        patch(
            marc(),
            "/api/boards/101",
            "{\"title\":\"Sprint 13\",\"description\":\"\",\"background\":\"forest\",\"showCardNumbers\":false}");
    assertStatus(r, 200);
    JsonNode b = json(r);
    assertThat(b.get("title").asString()).isEqualTo("Sprint 13");
    assertThat(b.get("description").isNull()).isTrue();
    assertThat(b.get("background").asString()).isEqualTo("forest");
    assertThat(b.get("showCardNumbers").asBoolean()).isFalse();
    assertThat(b.get("listCount").asInt()).as("FA11 : réponse sans compteurs").isZero();
    assertThat(b.get("cardCount").asInt()).isZero();
    assertThat(b.get("role").asString()).isEqualTo("member");
    assertThat(b.get("members")).hasSize(4);
    assertThat(b.has("lists")).as("BoardSummary seulement").isFalse();
    Map<String, Object> row = row();
    assertThat(row.get("title")).isEqualTo("Sprint 13");
    assertThat(row.get("description")).isNull();
    assertThat(row.get("background")).isEqualTo("forest");
    assertThat(((Number) row.get("show_card_numbers")).intValue()).isZero();
  }

  @Test
  @DisplayName("SC-BOARD-005 — champs absents inchangés ; fond hors liste ignoré")
  void absentFieldsUnchanged() {
    Response r = patch(alice(), "/api/boards/101", "{\"background\":\"rainbow\"}");
    assertStatus(r, 200);
    assertThat(row())
        .containsEntry("title", Fixture.LAUNCH_TITLE)
        .containsEntry("description", "Plan de lancement")
        .containsEntry("background", "ocean");
  }

  @ParameterizedTest(name = "description {0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"0\"|<null>",
        "null|<null>",
        "\"\"|<null>",
        "\"  garde  \"|  garde  ",
        "\"00\"|00"
      })
  @DisplayName(
      "SC-BOARD-005 — description : \"\" et \"0\" → null (falsy PHP), sinon stockée telle quelle")
  void description(String value, String expected) {
    assertStatus(patch(alice(), "/api/boards/101", "{\"description\":" + value + "}"), 200);
    assertThat(row().get("description")).isEqualTo("<null>".equals(expected) ? null : expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"title\":\"\",\"background\":\"forest\"}|Le champ \"title\" est obligatoire.",
        "{\"title\":\"  \"}|Le champ \"title\" est obligatoire.",
        "{\"title\":42,\"background\":\"forest\"}|Le champ \"title\" doit être une chaîne de caractères.",
        "{\"title\":null}|Le champ \"title\" doit être une chaîne de caractères.",
        "{\"description\":7,\"background\":\"forest\"}|Le champ \"description\" doit être une chaîne de caractères.",
        "{\"showCardNumbers\":\"false\",\"title\":\"X\"}|Le champ \"showCardNumbers\" doit être un booléen.",
        "{\"showCardNumbers\":0}|Le champ \"showCardNumbers\" doit être un booléen.",
        "{\"showCardNumbers\":null}|Le champ \"showCardNumbers\" doit être un booléen."
      })
  @DisplayName("SC-BOARD-005, E22, E23 — 400 exact, aucune écriture partielle")
  void invalid(String body, String message) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(patch(alice(), "/api/boards/101", body), 400, message);
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName("SC-BOARD-005 — titre trimé et tronqué à 160 points de code")
  void titleNormalized() {
    assertStatus(
        patch(alice(), "/api/boards/101", "{\"title\":\"  " + "é".repeat(170) + "  \"}"), 200);
    assertThat((String) row().get("title")).isEqualTo("é".repeat(160));
  }
}
