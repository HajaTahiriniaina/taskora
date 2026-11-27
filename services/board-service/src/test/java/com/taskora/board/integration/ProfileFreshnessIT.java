package com.taskora.board.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-027 (B-ADR-2 point 4, B-ADR-10) — nom, e-mail et couleur relus dans {@code taskora.user}
 * à chaque requête : un changement de profil (projection écrite par identity) est visible à la
 * requête suivante, sans cache (R9).
 */
class ProfileFreshnessIT extends ApiTestBase {

  private static JsonNode carlaIn(JsonNode members) {
    for (JsonNode m : members) {
      if (m.get("id").asInt() == Fixture.CARLA) {
        return m;
      }
    }
    throw new AssertionError("Carla absente");
  }

  @Test
  @DisplayName(
      "SC-BOARD-027 — changement de name et avatar_color entre deux requêtes → visible dans members (coquille et accueil)")
  void profileReadEveryTime() {
    JsonNode before = carlaIn(json(get(alice(), "/api/boards/101")).get("members"));
    assertThat(before.get("name").asString()).isEqualTo("Carla Membre");
    assertThat(before.get("avatarColor").asString()).isEqualTo("#14b8a6");

    BoardDatabase.rootExecute(
        "UPDATE taskora.`user` SET name = 'Carla Renommée', avatar_color = '#123456' WHERE id = ?",
        Fixture.CARLA);

    JsonNode after = carlaIn(json(get(alice(), "/api/boards/101")).get("members"));
    assertThat(after.get("name").asString()).isEqualTo("Carla Renommée");
    assertThat(after.get("avatarColor").asString()).isEqualTo("#123456");
    JsonNode summary = json(get(alice(), "/api/boards"));
    for (JsonNode b : summary) {
      if (b.get("id").asInt() == Fixture.LAUNCH) {
        assertThat(carlaIn(b.get("members")).get("name").asString()).isEqualTo("Carla Renommée");
      }
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-027 — le nom de l'appelant dans une notification est celui de la base au moment de l'action")
  void actorNameFresh() {
    BoardDatabase.rootExecute(
        "UPDATE taskora.`user` SET name = 'Alice Nouvelle' WHERE id = ?", Fixture.ALICE);
    assertStatus(
        post(alice(), "/api/boards/101/members", "{\"email\":\"dora@exemple.test\"}"), 201);
    assertThat(outbox("symfony-notifications"))
        .anySatisfy(m ->
            assertThat(String.valueOf(m.get("payload")))
                .contains("Alice Nouvelle vous a ajouté au tableau « Lancement produit »"));
  }
}
