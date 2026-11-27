package com.taskora.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SC-BOARD-003, 004, 018, 029 — GET /api/boards/{id} (coquille). */
class GetBoardIT extends ApiTestBase {

  @Test
  @DisplayName(
      "SC-BOARD-003 / 029 — coquille : listes ouvertes triées position, id (égalité 202/205), cardCount, compteurs = sommes")
  void shell() {
    Response r = get(marc(), "/api/boards/101");
    assertStatus(r, 200);
    JsonNode b = json(r);
    List<String> lists = new ArrayList<>();
    for (JsonNode l : b.get("lists")) {
      lists.add(
          l.get("id").asInt() + ":" + l.get("position").asInt() + ":" + l.get("cardCount").asInt());
    }
    assertThat(lists).containsExactly("201:0:3", "202:1:2", "205:1:0");
    assertThat(b.get("listCount").asInt()).isEqualTo(3);
    assertThat(b.get("cardCount").asInt()).isEqualTo(5);
    JsonNode enCours = b.get("lists").get(1);
    assertThat(enCours.get("title").asString()).isEqualTo("En cours");
    assertThat(enCours.get("color").asString()).isEqualTo("#6366f1");
    assertThat(enCours.get("archivedAt").isNull()).isTrue();
    assertThat(b.get("role").asString()).isEqualTo("member");
    assertThat(b.get("favorite").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "SC-BOARD-003 — étiquettes par id, champs position puis id (options relues telles qu'écrites)")
  void labelsAndFields() {
    JsonNode b = json(get(alice(), "/api/boards/101"));
    assertThat(b.get("labels").toString())
        .isEqualTo(
            "[{\"id\":501,\"name\":\"Urgent\",\"color\":\"#ef4444\"},{\"id\":502,\"name\":\"Bug\",\"color\":\"#f97316\"}]");
    assertThat(b.get("customFields")).hasSize(2);
    JsonNode select = b.get("customFields").get(0);
    assertThat(select.get("id").asInt()).isEqualTo(601);
    assertThat(select.get("name").asString()).isEqualTo("Priorité client");
    assertThat(select.get("type").asString()).isEqualTo("select");
    assertThat(select.get("showOnCard").asBoolean()).isTrue();
    assertThat(select.get("position").asInt()).isZero();
    assertThat(select.get("options").toString())
        .isEqualTo("[{\"id\":\"a1\",\"label\":\"Haute\"},{\"id\":\"b2\",\"label\":\"Basse\"}]");
    assertThat(b.get("customFields").get(1).get("id").asInt()).isEqualTo(602);
  }

  @Test
  @DisplayName(
      "SC-BOARD-018 — invitations visibles par un simple membre ; dates ATOM (heure ambiguë d'octobre → +02:00)")
  void invitationsVisibleToMember() {
    JsonNode inv = json(get(marc(), "/api/boards/101")).get("invitations");
    assertThat(inv).hasSize(2);
    assertThat(inv.get(0).get("id").asInt()).isEqualTo(Fixture.INVITATION_INES_LAUNCH);
    assertThat(inv.get(0).get("email").asString()).isEqualTo("invite@exemple.test");
    assertAtom(inv.get(0).get("createdAt"), "2026-11-23T16:45:00+01:00");
    assertThat(inv.get(1).get("email").asString()).isEqualTo("autre@exemple.test");
    assertAtom(inv.get(1).get("createdAt"), "2026-10-25T02:30:00+02:00");
  }

  @Test
  @DisplayName("SC-BOARD-003 — trelloFiles = fichiers Trello dont le statut n'est pas done")
  void trelloFiles() {
    assertThat(json(get(alice(), "/api/boards/101")).get("trelloFiles").asInt()).isEqualTo(2);
    assertThat(json(get(alice(), "/api/boards/102")).get("trelloFiles").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-BOARD-004 — inexistant → 404 Tableau introuvable. ; corbeille (membre) → Ce tableau est dans la corbeille.")
  void notFoundAndTrash() {
    assertError(get(alice(), "/api/boards/" + Fixture.MISSING_BOARD), 404, "Tableau introuvable.");
    assertError(
        get(alice(), "/api/boards/" + Fixture.OLD_TRASHED),
        404,
        "Ce tableau est dans la corbeille.");
    assertError(
        get(bruno(), "/api/boards/" + Fixture.OLD_TRASHED),
        404,
        "Ce tableau est dans la corbeille.");
  }

  @Test
  @DisplayName("SC-BOARD-003 — tableau sans invitation ni champ : tableaux vides, pas de null")
  void emptyCollections() {
    JsonNode b = json(get(alice(), "/api/boards/102"));
    assertThat(b.get("customFields").isArray()).isTrue();
    assertThat(b.get("customFields")).isEmpty();
    assertThat(b.get("invitations")).hasSize(1);
    assertThat(b.get("showCardNumbers").asBoolean()).isFalse();
    assertThat(b.get("description").isNull()).isTrue();
  }
}
