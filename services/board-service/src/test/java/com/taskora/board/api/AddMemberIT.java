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

/** SC-BOARD-009, 010, 011, 013, 031, E24 — POST /api/boards/{id}/members. */
class AddMemberIT extends ApiTestBase {
  private static final String PATH = "/api/boards/101/members";

  @Test
  @DisplayName(
      "SC-BOARD-009 — compte existant : e-mail trim + minuscules, rôle admin → 201 added + membre exact")
  void addExisting() {
    Response r = post(alice(), PATH, "{\"email\":\" Dora@Exemple.TEST \",\"role\":\"admin\"}");
    assertStatus(r, 201);
    JsonNode b = json(r);
    assertThat(b.get("status").asString()).isEqualTo("added");
    JsonNode m = b.get("member");
    assertThat(m.get("id").asInt()).isEqualTo(Fixture.DORA);
    assertThat(m.get("name").asString()).isEqualTo("Dora Libre");
    assertThat(m.get("email").asString()).isEqualTo(Fixture.DORA_EMAIL);
    assertThat(m.get("avatarColor").asString()).isEqualTo("#eab308");
    assertThat(m.get("role").asString()).isEqualTo("admin");
    assertAtom(m.get("joinedAt"), "2026-11-27T11:00:00+01:00");
    assertThat(
            query(
                "SELECT role, favorite FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                Fixture.DORA))
        .containsExactly(Map.of("role", "admin", "favorite", 0));
  }

  @ParameterizedTest(name = "role {0} → member")
  @CsvSource({"'\"owner\"'", "'\"superadmin\"'", "42", "null", "'\"Admin\"'"})
  @DisplayName("SC-BOARD-009 / 032 — role owner, inconnu, non chaîne → member")
  void roleDefaultsToMember(String role) {
    assertStatus(
        post(alice(), PATH, "{\"email\":\"dora@exemple.test\",\"role\":" + role + "}"), 201);
    assertThat(
            scalar(
                "SELECT role FROM taskora.board_member WHERE board_id = 101 AND user_id = ?",
                Fixture.DORA))
        .isEqualTo("member");
  }

  @Test
  @DisplayName("SC-BOARD-009 — sans role → member ; un admin peut ajouter")
  void adminAdds() {
    Response r = post(bruno(), PATH, "{\"email\":\"dora@exemple.test\"}");
    assertStatus(r, 201);
    assertThat(json(r).get("member").get("role").asString()).isEqualTo("member");
  }

  @Test
  @DisplayName(
      "SC-BOARD-031 — compte stocké avec une autre casse : trouvé par la collation de user.email → ajout direct")
  void collationFindsLegacyAccount() {
    Response r = post(alice(), PATH, "{\"email\":\"gaston.vieux@exemple.test\"}");
    assertStatus(r, 201);
    assertThat(json(r).get("member").get("id").asInt()).isEqualTo(Fixture.GASTON);
    assertThat(json(r).get("member").get("email").asString())
        .isEqualTo(Fixture.GASTON_STORED_EMAIL);
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email LIKE 'gaston%'"))
        .isZero();
  }

  @Test
  @DisplayName("SC-BOARD-009 — déjà membre → 409 Cet utilisateur est déjà membre du tableau.")
  void alreadyMember() {
    assertError(
        post(alice(), PATH, "{\"email\":\"CARLA@exemple.test\"}"),
        409,
        "Cet utilisateur est déjà membre du tableau.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-011 — aucun compte : 202 invited, invitation persistée (normalisée) et visible dans la coquille")
  void invite() {
    Response r = post(alice(), PATH, "{\"email\":\" Nouveau@Exemple.test \",\"role\":\"admin\"}");
    assertStatus(r, 202);
    assertThat(json(r).toString())
        .isEqualTo(
            "{\"status\":\"invited\",\"message\":\"Invitation enregistrée : nouveau@exemple.test rejoindra le tableau à son inscription.\"}");
    Map<String, Object> inv =
        query(
                "SELECT email, invited_by_id, created_at FROM taskora.invitation WHERE board_id = 101 AND email = 'nouveau@exemple.test'")
            .getFirst();
    assertThat(inv.get("email")).isEqualTo("nouveau@exemple.test");
    assertThat(inv.get("invited_by_id")).isEqualTo(Fixture.ALICE);
    assertThat(String.valueOf(inv.get("created_at"))).isEqualTo("2026-11-27T11:00");
    assertThat(json(get(marc(), "/api/boards/101")).get("invitations").toString())
        .contains("nouveau@exemple.test");
    assertThat(outbox("symfony-notifications"))
        .as("aucun e-mail ni notification pour une invitation")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "SC-BOARD-011 / 031 — même adresse réinvitée (autre casse) → 409 Une invitation est déjà en attente pour cette adresse.")
  void alreadyInvited() {
    assertError(
        post(bruno(), PATH, "{\"email\":\"AUTRE@exemple.test\"}"),
        409,
        "Une invitation est déjà en attente pour cette adresse.");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"email\":\"pas-un-email\"}|Adresse e-mail invalide.",
        "{\"email\":\"\"}|Le champ \"email\" est obligatoire.",
        "{\"email\":\"   \"}|Le champ \"email\" est obligatoire.",
        "{}|Le champ \"email\" est obligatoire.",
        "{\"email\":42}|Le champ \"email\" doit être une chaîne de caractères.",
        "{\"email\":null}|Le champ \"email\" doit être une chaîne de caractères.",
        "{\"email\":[\"a@b.fr\"]}|Le champ \"email\" doit être une chaîne de caractères."
      })
  @DisplayName(
      "SC-BOARD-011, E22 — e-mail invalide, vide ou non chaîne → 400 exact, aucune écriture")
  void invalidEmail(String body, String message) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(post(alice(), PATH, body), 400, message);
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "SC-BOARD-031 — e-mail de 200 caractères : tronqué à 180 puis 400 Adresse e-mail invalide.")
  void longEmail() {
    String email = "a".repeat(60) + "@" + "b".repeat(60) + "." + "c".repeat(60) + ".fr";
    assertError(
        post(alice(), PATH, "{\"email\":\"" + email + "\"}"), 400, "Adresse e-mail invalide.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-010 — simple membre → 403 Action réservée aux administrateurs du tableau., aucune écriture")
  void memberForbidden() {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(
        post(marc(), PATH, "{\"email\":\"dora@exemple.test\"}"),
        403,
        "Action réservée aux administrateurs du tableau.");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName("SC-BOARD-009 — événement MemberAdded (broker, HELD, via direct)")
  void memberAddedEvent() {
    assertStatus(post(alice(), PATH, "{\"email\":\"dora@exemple.test\",\"role\":\"admin\"}"), 201);
    assertThat(outbox("broker"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.get("type")).isEqualTo("MemberAdded");
          assertThat(e.get("status")).isEqualTo("HELD");
          JsonNode p = json(String.valueOf(e.get("payload")));
          assertThat(p.get("userId").asInt()).isEqualTo(Fixture.DORA);
          assertThat(p.get("role").asString()).isEqualTo("admin");
          assertThat(p.get("via").asString()).isEqualTo("direct");
          assertThat(p.get("boardId").asInt()).isEqualTo(101);
          assertThat(p.get("actorId").asInt()).isEqualTo(Fixture.ALICE);
        });
  }
}
