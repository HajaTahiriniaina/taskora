package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.OpenApiValidation;
import com.taskora.identity.support.TokenFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** POST /api/auth/login (SC-IDENT-007, 008, 009, 017, 032, 033). */
class LoginApiIT extends ApiTestBase {
  private static final String BAD = "Invalid credentials.";

  private void camille() throws Exception {
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
  }

  @Test
  @DisplayName("SC-IDENT-007 — connexion nominale : 200 {token, user}, le jeton ouvre GET /api/me")
  void nominal() throws Exception {
    camille();
    MvcResult r = login("camille@exemple.fr", "motdepasse");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    OpenApiValidation.assertResponseConforms(r);
    JsonNode b = json(r);
    assertThat(new java.util.ArrayList<>(b.propertyNames())).containsExactlyInAnyOrder("token", "user");
    assertUserJson(b.get("user"), "Camille", "camille@exemple.fr", null);
    String token = b.get("token").asString();
    JsonNode claims = JSON.readTree(TokenFactory.decodePart(token, 1));
    assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(604_800L);
    MvcResult me = me(token);
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    assertThat(json(me).get("email").asString()).isEqualTo("camille@exemple.fr");
  }

  @ParameterizedTest(name = "« {0} » → 200")
  @ValueSource(strings = {"Camille@Exemple.fr", "CAMILLE@EXEMPLE.FR", "camille@exemple.fr ", "camille@exemple.fr   "})
  @DisplayName("SC-IDENT-007 — collation utf8mb4_unicode_ci : casse et espaces finaux ignorés")
  void collationCaseAndTrailingSpaces(String email) throws Exception {
    camille();
    MvcResult r = login(email, "motdepasse");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    assertThat(json(r).get("user").get("email").asString()).isEqualTo("camille@exemple.fr");
  }

  @Test
  @DisplayName("SC-IDENT-007 — collation : accents ignorés (e ↔ é)")
  void collationAccents() throws Exception {
    registeredToken("Élève", "eleve@exemple.fr", "motdepasse");
    MvcResult r = login("élève@exemple.fr", "motdepasse");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    assertThat(json(r).get("user").get("email").asString()).isEqualTo("eleve@exemple.fr");
  }

  @Test
  @DisplayName("SC-IDENT-007 — espace initial significatif → 401")
  void leadingSpace() throws Exception {
    camille();
    assertAuthError(login(" camille@exemple.fr", "motdepasse"), BAD);
  }

  @Test
  @DisplayName("SC-IDENT-008 — mauvais mot de passe et e-mail inconnu : même 401, mêmes corps et en-têtes")
  void sameBodyForUnknownAndWrong() throws Exception {
    camille();
    MvcResult wrong = login("camille@exemple.fr", "mauvaismdp");
    MvcResult unknown = login("inconnu@exemple.fr", "motdepasse");
    assertAuthError(wrong, BAD);
    assertAuthError(unknown, BAD);
    assertThat(body(wrong)).isEqualTo(body(unknown));
    assertThat(wrong.getResponse().getHeader("WWW-Authenticate"))
        .isEqualTo(unknown.getResponse().getHeader("WWW-Authenticate"));
  }

  @Test
  @DisplayName("SC-IDENT-008/028 — mot de passe de plus de 4096 octets au login → 401 (inchangé)")
  void longPasswordIs401() throws Exception {
    camille();
    assertAuthError(login("camille@exemple.fr", "a".repeat(4097)), BAD);
  }

  @Test
  @DisplayName("SC-IDENT-008 — e-mail de plus de 4096 octets → 401")
  void longEmailIs401() throws Exception {
    assertAuthError(login("a".repeat(4097) + "@exemple.fr", "motdepasse"), BAD);
  }

  @Test
  @DisplayName("SC-IDENT-008 — le corps 401 ne révèle ni l'e-mail ni le mot de passe")
  void noEcho() throws Exception {
    camille();
    String b = body(login("camille@exemple.fr", "mauvais-secret"));
    assertThat(b).doesNotContain("camille").doesNotContain("mauvais-secret");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          tableau vide                       | []                                          | JSON invalide.
          chaîne JSON                        | "texte"                                     | JSON invalide.
          nombre JSON                        | 42                                          | JSON invalide.
          non JSON                           | pas du json                                 | JSON invalide.
          email nombre                       | {"email":123,"password":"x"}                | Le champ "email" doit être une chaîne de caractères.
          email null                         | {"email":null,"password":"x"}               | Le champ "email" doit être une chaîne de caractères.
          email objet                        | {"email":{},"password":"x"}                 | Le champ "email" doit être une chaîne de caractères.
          email type avant password absent   | {"email":123}                               | Le champ "email" doit être une chaîne de caractères.
          password null                      | {"email":"a@b.c","password":null}           | Le champ "password" doit être une chaîne de caractères.
          password booléen                   | {"email":"a@b.c","password":false}          | Le champ "password" doit être une chaîne de caractères.
          password type avant email absent   | {"password":123}                            | Le champ "password" doit être une chaîne de caractères.
          email absent                       | {"password":"motdepasse"}                   | Le champ "email" est obligatoire.
          objet vide                         | {}                                          | Le champ "email" est obligatoire.
          email vide                         | {"email":"","password":"x"}                 | Le champ "email" est obligatoire.
          password absent                    | {"email":"camille@exemple.fr"}              | Le champ "password" est obligatoire.
          password vide                      | {"email":"camille@exemple.fr","password":""} | Le champ "password" est obligatoire.
          """)
  @DisplayName("SC-IDENT-009/032 E19 — 400 en français, message exact ; ordre forme → types → présences")
  void frenchValidation(String label, String body, String message) throws Exception {
    assertError(postJson("/api/auth/login", body), 400, message);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\n\t"})
  @DisplayName("SC-IDENT-033 E20 — corps vide ou d'espaces → 400 « JSON invalide. »")
  void emptyBody(String body) throws Exception {
    assertError(postJson("/api/auth/login", body), 400, "JSON invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-032 E19 — Content-Type absent : la requête est traitée normalement (plus de 404)")
  void noContentType() throws Exception {
    camille();
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .content("{\"email\":\"camille@exemple.fr\",\"password\":\"motdepasse\"}".getBytes(StandardCharsets.UTF_8)))
            .andReturn();
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
  }

  @Test
  @DisplayName("SC-IDENT-032 E19 — Content-Type text/plain : traité normalement (plus de 404)")
  void textPlain() throws Exception {
    camille();
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("text/plain")
                    .content("{\"email\":\"camille@exemple.fr\",\"password\":\"mauvais\"}".getBytes(StandardCharsets.UTF_8)))
            .andReturn();
    assertAuthError(r, BAD);
  }

  @Test
  @DisplayName("SC-IDENT-032 E19 — aucun message anglais de json_login")
  void noEnglishMessage() throws Exception {
    for (String b : new String[] {"{}", "{\"email\":1}", "{\"email\":\"a@b.c\"}"}) {
      MvcResult r = postJson("/api/auth/login", b);
      assertThat(r.getResponse().getStatus()).as(b).isEqualTo(400);
      assertThat(body(r)).doesNotContain("must be").doesNotContain("Invalid JSON").contains("Le champ").containsAnyOf("obligatoire", "chaîne");
    }
  }

  @Test
  @DisplayName("SC-IDENT-009 — GET /api/auth/login → 405 au format {\"error\"}")
  void methodNotAllowed() throws Exception {
    MvcResult r = mvc.perform(get("/api/auth/login")).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(405);
    assertThat(new java.util.ArrayList<>(json(r).propertyNames())).containsExactly("error");
  }

  @Test
  @DisplayName("SC-IDENT-017 — après changement de mot de passe : ancien → 401, nouveau → 200")
  void afterPasswordChange() throws Exception {
    String token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
    MvcResult p = patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}");
    assertThat(p.getResponse().getStatus()).as(body(p)).isEqualTo(200);
    assertAuthError(login("camille@exemple.fr", "ancienmdp1"), BAD);
    assertThat(login("camille@exemple.fr", "nouveaumdp1").getResponse().getStatus()).isEqualTo(200);
  }
}
