package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-001, SC-BOARD-028, SC-CARD-043 (E22, E23) — POST /api/boards. */
class CreateBoardIT extends ApiTestBase {

  private Response create(String body) {
    return post(alice(), "/api/boards", body);
  }

  @Test
  @DisplayName(
      "SC-BOARD-001 — 201 : propriétaire = appelant, 4 listes et 6 étiquettes par défaut, coquille complète")
  void nominal() {
    Response r = create("{\"title\":\"Sprint 12\",\"background\":\"ocean\"}");
    assertStatus(r, 201);
    JsonNode b = json(r);
    int id = b.get("id").asInt();
    assertThat(b.get("title").asString()).isEqualTo("Sprint 12");
    assertThat(b.get("description").isNull()).isTrue();
    assertThat(b.get("background").asString()).isEqualTo("ocean");
    assertThat(b.get("showCardNumbers").asBoolean()).isTrue();
    assertThat(b.get("favorite").asBoolean()).isFalse();
    assertThat(b.get("role").asString()).isEqualTo("owner");
    assertAtom(b.get("createdAt"), "2026-11-27T11:00:00+01:00");
    assertThat(b.get("members")).hasSize(1);
    JsonNode owner = b.get("members").get(0);
    assertThat(owner.get("id").asInt()).isEqualTo(Fixture.ALICE);
    assertThat(owner.get("name").asString()).isEqualTo("Alice Martin");
    assertThat(owner.get("email").asString()).isEqualTo(Fixture.ALICE_EMAIL);
    assertThat(owner.get("avatarColor").asString()).isEqualTo("#6366f1");
    assertThat(owner.get("role").asString()).isEqualTo("owner");
    assertAtom(owner.get("joinedAt"), "2026-11-27T11:00:00+01:00");
    assertThat(b.get("trelloFiles").asInt()).isZero();
    assertThat(b.get("invitations")).isEmpty();
    assertThat(b.get("customFields")).isEmpty();
    assertThat(b.get("cardCount").asInt()).isZero();
    assertThat(b.get("listCount").asInt())
        .as("Presenter::board : nombre de listes créées")
        .isEqualTo(4);

    List<String> lists = new ArrayList<>();
    for (JsonNode l : b.get("lists")) {
      lists.add(l.get("title").asString() + "@" + l.get("position").asInt());
      assertThat(l.get("cardCount").asInt()).isZero();
      assertThat(l.get("color").isNull()).isTrue();
      assertThat(l.get("archivedAt").isNull()).isTrue();
    }
    assertThat(lists).containsExactly("À faire@0", "En cours@1", "En revue@2", "Terminé@3");
    List<String> labels = new ArrayList<>();
    for (JsonNode l : b.get("labels")) {
      labels.add(l.get("name").asString() + " " + l.get("color").asString());
    }
    assertThat(labels)
        .containsExactly(
            "Urgent #ef4444",
            "Bug #f97316",
            "Feature #6366f1",
            "Design #ec4899",
            "Backend #14b8a6",
            "Idée #eab308");

    Map<String, Object> row = query("SELECT * FROM taskora.board WHERE id = ?", id).getFirst();
    assertThat(row.get("owner_id")).isEqualTo(Fixture.ALICE);
    assertThat(row.get("background")).isEqualTo("ocean");
    assertThat(row.get("description")).isNull();
    assertThat(((Number) row.get("card_seq")).intValue()).isZero();
    assertThat(((Number) row.get("show_card_numbers")).intValue()).isEqualTo(1);
    assertThat(row.get("deleted_at")).isNull();
    assertThat(String.valueOf(row.get("created_at"))).startsWith("2026-11-27").contains("11:00");
    assertThat(
            query(
                "SELECT role, favorite, user_id FROM taskora.board_member WHERE board_id = ?", id))
        .containsExactly(Map.of("role", "owner", "favorite", 0, "user_id", Fixture.ALICE));
    assertThat(count("SELECT COUNT(*) FROM taskora.board_list WHERE board_id = ?", id))
        .isEqualTo(4);
    assertThat(count("SELECT COUNT(*) FROM taskora.label WHERE board_id = ?", id)).isEqualTo(6);
  }

  @Test
  @DisplayName("SC-BOARD-001 — withDefaults:false → aucune liste ni étiquette")
  void withoutDefaults() {
    Response r = create("{\"title\":\"Vide\",\"withDefaults\":false}");
    assertStatus(r, 201);
    int id = json(r).get("id").asInt();
    assertThat(json(r).get("lists")).isEmpty();
    assertThat(json(r).get("labels")).isEmpty();
    assertThat(json(r).get("listCount").asInt()).isZero();
    assertThat(count("SELECT COUNT(*) FROM taskora.board_list WHERE board_id = ?", id)).isZero();
    assertThat(count("SELECT COUNT(*) FROM taskora.label WHERE board_id = ?", id)).isZero();
  }

  @ParameterizedTest(name = "background = {0}")
  @CsvSource({"'\"rainbow\"'", "'\"Ocean\"'", "42", "null", "'[\"ocean\"]'"})
  @DisplayName("SC-BOARD-001 — background hors liste ou non chaîne : ignoré (aurora)")
  void backgroundIgnored(String background) {
    Response r = create("{\"title\":\"Fond\",\"background\":" + background + "}");
    assertStatus(r, 201);
    assertThat(json(r).get("background").asString()).isEqualTo("aurora");
  }

  @Test
  @DisplayName(
      "SC-BOARD-001 — description stockée telle quelle (ni trim ni troncature) ; null → null")
  void rawDescription() {
    String raw = "  Brut \\n avec espaces  ";
    Response r = create("{\"title\":\"Desc\",\"description\":\"" + raw + "\"}");
    assertStatus(r, 201);
    assertThat(json(r).get("description").asString()).isEqualTo("  Brut \n avec espaces  ");
    assertThat(
            scalar("SELECT description FROM taskora.board WHERE id = ?", json(r).get("id").asInt()))
        .isEqualTo("  Brut \n avec espaces  ");
    Response n = create("{\"title\":\"Desc\",\"description\":null}");
    assertThat(json(n).get("description").isNull()).isTrue();
  }

  @Test
  @DisplayName("SC-BOARD-001 — titre de 200 caractères → tronqué à 160 ; vide ou absent → 400")
  void title() {
    Response r = create("{\"title\":\"" + "t".repeat(200) + "\"}");
    assertStatus(r, 201);
    assertThat(json(r).get("title").asString()).hasSize(160);
    assertError(create("{\"title\":\"\"}"), 400, "Le champ \"title\" est obligatoire.");
    assertError(create("{}"), 400, "Le champ \"title\" est obligatoire.");
    assertError(create("{\"description\":\"x\"}"), 400, "Le champ \"title\" est obligatoire.");
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "title|42|Le champ \"title\" doit être une chaîne de caractères.",
        "title|true|Le champ \"title\" doit être une chaîne de caractères.",
        "title|[\"a\"]|Le champ \"title\" doit être une chaîne de caractères.",
        "title|{\"a\":1}|Le champ \"title\" doit être une chaîne de caractères.",
        "title|null|Le champ \"title\" doit être une chaîne de caractères.",
        "description|42|Le champ \"description\" doit être une chaîne de caractères.",
        "description|false|Le champ \"description\" doit être une chaîne de caractères.",
        "withDefaults|\"yes\"|Le champ \"withDefaults\" doit être un booléen.",
        "withDefaults|1|Le champ \"withDefaults\" doit être un booléen.",
        "withDefaults|null|Le champ \"withDefaults\" doit être un booléen."
      })
  @DisplayName("SC-CARD-043 (E22, E23) — type JSON inattendu → 400, aucune écriture")
  void types(String field, String value, String message) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    String body =
        "title".equals(field)
            ? "{\"title\":" + value + "}"
            : "{\"title\":\"Typé\",\"" + field + "\":" + value + "}";
    assertError(create(body), 400, message);
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName("SC-BOARD-001 — ordre des contrôles : types (E23) avant le titre obligatoire")
  void typesBeforeTitle() {
    assertError(
        create("{\"title\":\"\",\"withDefaults\":\"x\"}"),
        400,
        "Le champ \"withDefaults\" doit être un booléen.");
  }

  @Test
  @DisplayName("SC-BOARD-001 — événement BoardCreated écrit dans l'outbox (broker, HELD)")
  void boardCreatedEvent() {
    Response r = create("{\"title\":\"Événement\"}");
    int id = json(r).get("id").asInt();
    List<Map<String, Object>> events = outbox("broker");
    assertThat(events).hasSize(1);
    Map<String, Object> e = events.getFirst();
    assertThat(e.get("type")).isEqualTo("BoardCreated");
    assertThat(e.get("status")).isEqualTo("HELD");
    assertThat(((Number) e.get("aggregate_id")).intValue()).isEqualTo(id);
    JsonNode payload = json(String.valueOf(e.get("payload")));
    assertThat(payload.get("type").asString()).isEqualTo("BoardCreated");
    assertThat(payload.get("version").asInt()).isEqualTo(1);
    assertThat(payload.get("boardId").asInt()).isEqualTo(id);
    assertThat(payload.get("ownerId").asInt()).isEqualTo(Fixture.ALICE);
    assertThat(payload.get("actorId").asInt()).isEqualTo(Fixture.ALICE);
    assertThat(payload.get("eventId").asString()).isEqualTo(e.get("event_id"));
    assertThat(java.time.OffsetDateTime.parse(payload.get("occurredAt").asString()).toInstant())
        .as("occurredAt en UTC")
        .isEqualTo(Fixture.NOW);
    assertThat(outbox("symfony-notifications")).isEmpty();
  }

  @Test
  @DisplayName(
      "SC-BOARD-001 — une seule transaction : panne à l'insertion des étiquettes → 500, rien n'est écrit")
  void singleTransaction() {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    BoardDatabase.rootExecute(
        List.of(
            "CREATE TRIGGER taskora.it_fail_label BEFORE INSERT ON taskora.label"
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'panne simulée'"));
    try {
      assertError(create("{\"title\":\"Atomique\"}"), 500, "Erreur interne du serveur.");
    } finally {
      BoardDatabase.rootExecute(List.of("DROP TRIGGER IF EXISTS taskora.it_fail_label"));
    }
    assertThat(count("SELECT COUNT(*) FROM taskora.board WHERE title = 'Atomique'")).isZero();
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }
}
