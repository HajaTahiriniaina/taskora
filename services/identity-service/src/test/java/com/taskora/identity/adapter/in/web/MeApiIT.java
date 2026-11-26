package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.OpenApiValidation;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/** GET et PATCH /api/me (SC-IDENT-014 à 021, 028 à 031, 033, 034). */
class MeApiIT extends ApiTestBase {
  private static final String TOO_SHORT = "Le mot de passe doit contenir au moins 8 caractères.";
  private static final String WRONG_CURRENT = "Mot de passe actuel incorrect.";
  private static final String BAD_COLOR = "Couleur d'avatar invalide.";

  private String token;
  private String originalHash;
  private String originalColor;

  @BeforeEach
  void camille() throws Exception {
    token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
    originalHash = storedHash("camille@exemple.fr");
    originalColor = (String) row().get("avatar_color");
  }

  private static Map<String, Object> row() {
    return query("SELECT * FROM identity.user_account WHERE email = 'camille@exemple.fr'")
        .getFirst();
  }

  private static Map<String, Object> legacyRow() {
    return query("SELECT * FROM taskora.`user` WHERE email = 'camille@exemple.fr'").getFirst();
  }

  private void assertNothingChanged() {
    Map<String, Object> r = row();
    assertThat(r.get("name")).isEqualTo("Camille");
    assertThat(r.get("avatar_color")).isEqualTo(originalColor);
    assertThat(r.get("password_hash")).isEqualTo(originalHash);
    Map<String, Object> l = legacyRow();
    assertThat(l.get("name")).isEqualTo("Camille");
    assertThat(l.get("password")).isEqualTo(originalHash);
  }

  private MvcResult ok(MvcResult r) {
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    OpenApiValidation.assertResponseConforms(r);
    return r;
  }

  @Test
  @DisplayName(
      "SC-IDENT-014 — GET /api/me : {id, name, email, avatarColor} de SON compte, conforme au contrat")
  void getMe() throws Exception {
    registeredToken("Autre", "autre@exemple.fr", "motdepasse");
    MvcResult r = ok(me(token));
    assertUserJson(json(r), "Camille", "camille@exemple.fr", originalColor);
    assertThat(json(r).get("id").asInt()).isEqualTo(((Number) row().get("id")).intValue());
  }

  @Test
  @DisplayName("SC-IDENT-015 — nom trimé, persisté dans identity ET projeté dans taskora.user")
  void rename() throws Exception {
    MvcResult r = ok(patchMe(token, "{\"name\":\"  Camille M. \"}"));
    assertThat(json(r).get("name").asString()).isEqualTo("Camille M.");
    assertThat(row().get("name")).isEqualTo("Camille M.");
    assertThat(legacyRow().get("name")).isEqualTo("Camille M.");
  }

  @Test
  @DisplayName("SC-IDENT-015 — {\"name\":\"\"} → 400 « Le champ \"name\" est obligatoire. »")
  void emptyName() throws Exception {
    assertError(patchMe(token, "{\"name\":\"\"}"), 400, "Le champ \"name\" est obligatoire.");
    assertNothingChanged();
  }

  @Test
  @DisplayName("SC-IDENT-015/030 — {\"name\":null} → nom inchangé (null = absent)")
  void nullName() throws Exception {
    MvcResult r = ok(patchMe(token, "{\"name\":null}"));
    assertThat(json(r).get("name").asString()).isEqualTo("Camille");
  }

  @Test
  @DisplayName("SC-IDENT-015 — clé absente ({}) → 200, rien ne change")
  void emptyObject() throws Exception {
    ok(patchMe(token, "{}"));
    assertNothingChanged();
  }

  @Test
  @DisplayName("SC-IDENT-015 — 150 caractères → tronqué à 120")
  void longName() throws Exception {
    MvcResult r = ok(patchMe(token, "{\"name\":\"" + "n".repeat(150) + "\"}"));
    assertThat(json(r).get("name").asString()).hasSize(120);
  }

  @ParameterizedTest
  @ValueSource(strings = {"#0EA5E9", "#0ea5e9"})
  @DisplayName("SC-IDENT-016 — couleur #RRGGBB enregistrée telle quelle")
  void color(String color) throws Exception {
    MvcResult r = ok(patchMe(token, "{\"avatarColor\":\"" + color + "\"}"));
    assertThat(json(r).get("avatarColor").asString()).isEqualTo(color);
    assertThat(row().get("avatar_color")).isEqualTo(color);
    assertThat(legacyRow().get("avatar_color")).isEqualTo(color);
  }

  @Test
  @DisplayName("SC-IDENT-016 — avatarColor null → couleur inchangée")
  void nullColor() throws Exception {
    ok(patchMe(token, "{\"avatarColor\":null}"));
    assertNothingChanged();
  }

  @ParameterizedTest(name = "« {0} »")
  @ValueSource(strings = {"red", "#12345", "#1234567", "", "#0EA5E9\\n", " #0EA5E9", "#GGGGGG"})
  @DisplayName(
      "SC-IDENT-031 E18 — couleur invalide → 400 « Couleur d'avatar invalide. », nom envoyé non appliqué")
  void invalidColor(String jsonEscapedColor) throws Exception {
    assertError(
        patchMe(token, "{\"name\":\"Camille M.\",\"avatarColor\":\"" + jsonEscapedColor + "\"}"),
        400,
        BAD_COLOR);
    assertNothingChanged();
  }

  @Test
  @DisplayName(
      "SC-IDENT-031 E18 — {\"name\":\"\",\"avatarColor\":\"red\"} → message sur name (ordre)")
  void nameBeforeColor() throws Exception {
    assertError(
        patchMe(token, "{\"name\":\"\",\"avatarColor\":\"red\"}"),
        400,
        "Le champ \"name\" est obligatoire.");
  }

  @Test
  @DisplayName(
      "SC-IDENT-017 — changement de mot de passe : 200, hash $2y$ changé dans identity et taskora.user")
  void changePassword() throws Exception {
    ok(patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}"));
    String newHash = storedHash("camille@exemple.fr");
    assertThat(newHash).isNotEqualTo(originalHash).startsWith("$2y$");
    assertThat(legacyRow().get("password")).isEqualTo(newHash);
  }

  @Test
  @DisplayName(
      "SC-IDENT-018 — currentPassword faux → 400 « Mot de passe actuel incorrect. », hash inchangé")
  void wrongCurrent() throws Exception {
    assertError(
        patchMe(token, "{\"currentPassword\":\"faux\",\"newPassword\":\"nouveaumdp1\"}"),
        400,
        WRONG_CURRENT);
    assertNothingChanged();
  }

  @Test
  @DisplayName(
      "SC-IDENT-018 — currentPassword absent avec newPassword → 400 « Mot de passe actuel incorrect. »")
  void missingCurrent() throws Exception {
    assertError(patchMe(token, "{\"newPassword\":\"nouveaumdp1\"}"), 400, WRONG_CURRENT);
    assertNothingChanged();
  }

  @Test
  @DisplayName(
      "SC-IDENT-018 — newPassword de 7 avec bon mot de passe actuel → 400 longueur, hash inchangé")
  void newTooShort() throws Exception {
    assertError(
        patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"1234567\"}"),
        400,
        TOO_SHORT);
    assertNothingChanged();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"newPassword\":\"\"}",
        "{\"newPassword\":null}",
        "{\"currentPassword\":\"faux\"}",
        "{\"currentPassword\":\"faux\",\"newPassword\":\"\"}"
      })
  @DisplayName(
      "SC-IDENT-018 E21 — newPassword absent, null ou \"\" → aucune vérification, aucun changement")
  void noPasswordChange(String body) throws Exception {
    ok(patchMe(token, body));
    assertNothingChanged();
  }

  @Test
  @DisplayName(
      "SC-IDENT-019 — {name, currentPassword faux, newPassword} → 400 et le nom reste « Camille »")
  void atomic() throws Exception {
    assertError(
        patchMe(
            token,
            "{\"name\":\"Camille M.\",\"currentPassword\":\"faux\",\"newPassword\":\"nouveaumdp1\"}"),
        400,
        WRONG_CURRENT);
    assertNothingChanged();
    assertThat(json(me(token)).get("name").asString()).isEqualTo("Camille");
  }

  @Test
  @DisplayName(
      "SC-IDENT-020 — {\"email\":\"autre@exemple.fr\"} → 200, e-mail inchangé, jeton toujours valide")
  void emailNotModifiable() throws Exception {
    MvcResult r = ok(patchMe(token, "{\"email\":\"autre@exemple.fr\"}"));
    assertThat(json(r).get("email").asString()).isEqualTo("camille@exemple.fr");
    assertThat(row().get("email")).isEqualTo("camille@exemple.fr");
    assertThat(me(token).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "SC-IDENT-021 — après changement de mot de passe, l'ancien jeton reste accepté (E10 conservé)")
  void oldTokenStillValid() throws Exception {
    String other = json(login("camille@exemple.fr", "ancienmdp1")).get("token").asString();
    ok(patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}"));
    assertThat(me(other).getResponse().getStatus()).isEqualTo(200);
    assertThat(me(token).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "SC-IDENT-028 E15 — newPassword de 4097 octets, bon mot de passe actuel → 400, hash inchangé")
  void newTooLong() throws Exception {
    assertError(
        patchMe(
            token,
            "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"" + "a".repeat(4097) + "\"}"),
        400,
        "Le mot de passe ne doit pas dépasser 4096 octets.");
    assertNothingChanged();
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — newPassword de 4096 octets → 200")
  void new4096() throws Exception {
    ok(
        patchMe(
            token,
            "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"" + "a".repeat(4096) + "\"}"));
    assertThat(login("camille@exemple.fr", "a".repeat(4096)).getResponse().getStatus())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "[1,2]", "\"texte\"", "42", "true", "null", "pas du json"})
  @DisplayName("SC-IDENT-029 E16 — corps non objet → 400 « JSON invalide. », rien n'est écrit")
  void nonObject(String body) throws Exception {
    assertError(patchMe(token, body), 400, "JSON invalide.");
    assertNothingChanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  @DisplayName("SC-IDENT-033 E20 — corps vide ou d'espaces → 400 « JSON invalide. »")
  void emptyBody(String body) throws Exception {
    assertError(patchMe(token, body), 400, "JSON invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-033 — PATCH sans jeton et corps vide → 401 d'abord")
  void unauthenticatedFirst() throws Exception {
    MvcResult r = mvc.perform(patch("/api/me")).andReturn();
    assertAuthError(r, "JWT Token not found");
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource({
    "name,nombre", "name,booléen", "name,tableau", "name,objet",
    "avatarColor,nombre", "avatarColor,booléen", "avatarColor,tableau", "avatarColor,objet",
    "currentPassword,nombre", "currentPassword,booléen", "currentPassword,tableau",
        "currentPassword,objet",
    "newPassword,nombre", "newPassword,booléen", "newPassword,tableau", "newPassword,objet"
  })
  @DisplayName(
      "SC-IDENT-030 E17 — champ non chaîne → 400 « Le champ \"x\" doit être une chaîne de caractères. »")
  void nonStringField(String field, String type) throws Exception {
    assertError(
        patchMe(token, "{\"" + field + "\":" + nonStringJson(type) + "}"), 400, typeError(field));
    assertNothingChanged();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          name avant les autres         | {"name":1,"avatarColor":2,"currentPassword":3,"newPassword":4} | name
          avatarColor avant mots de passe | {"avatarColor":2,"currentPassword":3,"newPassword":4}       | avatarColor
          currentPassword avant newPassword | {"currentPassword":3,"newPassword":4}                     | currentPassword
          type avant contenu de name    | {"name":"","newPassword":4}                                    | newPassword
          type avant couleur invalide   | {"avatarColor":"red","currentPassword":false}                  | currentPassword
          """)
  @DisplayName(
      "SC-IDENT-030 E17 — types contrôlés dans l'ordre name, avatarColor, currentPassword, newPassword, avant le contenu")
  void typeOrder(String label, String body, String field) throws Exception {
    assertError(patchMe(token, body), 400, typeError(field));
  }

  @Test
  @DisplayName(
      "SC-IDENT-034 E21 — {currentPassword correct, newPassword \"0\"} → 400 longueur, hash inchangé")
  void zeroWithCurrent() throws Exception {
    assertError(
        patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"0\"}"),
        400,
        TOO_SHORT);
    assertNothingChanged();
  }

  @Test
  @DisplayName(
      "SC-IDENT-034 E21 — {newPassword \"0\"} seul → 400 « Mot de passe actuel incorrect. »")
  void zeroWithoutCurrent() throws Exception {
    assertError(patchMe(token, "{\"newPassword\":\"0\"}"), 400, WRONG_CURRENT);
    assertNothingChanged();
  }

  @Test
  @DisplayName("SC-IDENT-034 E21 — newPassword \" \" (espace) contrôlé comme une chaîne")
  void spaceIsControlled() throws Exception {
    assertError(patchMe(token, "{\"newPassword\":\" \"}"), 400, WRONG_CURRENT);
  }
}
