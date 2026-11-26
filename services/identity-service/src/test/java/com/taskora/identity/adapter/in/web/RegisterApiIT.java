package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.OpenApiValidation;
import com.taskora.identity.support.TokenFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** POST /api/auth/register (SC-IDENT-001 à 004, 024, 028 à 030, 033). */
class RegisterApiIT extends ApiTestBase {
  private static final String TOO_SHORT = "Le mot de passe doit contenir au moins 8 caractères.";
  private static final String TOO_LONG = "Le mot de passe ne doit pas dépasser 4096 octets.";

  private static long accounts() {
    return count("SELECT COUNT(*) FROM identity.user_account");
  }

  @Test
  @DisplayName("SC-IDENT-001 — 201, jeton, utilisateur normalisé, couleur de la palette, hash $2y$ stocké, projection historique")
  void nominal() throws Exception {
    MvcResult r = register("  Camille Martin ", " Camille@Exemple.FR ", "motdepasse");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    OpenApiValidation.assertResponseConforms(r);
    JsonNode b = json(r);
    assertThat(new java.util.ArrayList<>(b.propertyNames())).containsExactlyInAnyOrder("token", "user");
    assertUserJson(b.get("user"), "Camille Martin", "camille@exemple.fr", null);
    assertThat(AvatarColor.PALETTE).contains(b.get("user").get("avatarColor").asString());

    JsonNode claims = JSON.readTree(TokenFactory.decodePart(b.get("token").asString(), 1));
    assertThat(claims.get("username").asString()).isEqualTo("camille@exemple.fr");
    assertThat(claims.get("uid").asInt()).isEqualTo(b.get("user").get("id").asInt());

    List<Map<String, Object>> rows = query("SELECT * FROM identity.user_account");
    assertThat(rows).hasSize(1);
    assertThat((String) rows.getFirst().get("password_hash")).startsWith("$2y$04$").hasSize(60);
    assertThat(rows.getFirst().get("email")).isEqualTo("camille@exemple.fr");
    assertThat(count("SELECT COUNT(*) FROM taskora.`user` WHERE email = 'camille@exemple.fr'")).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-001/024 — la réponse ne contient jamais le mot de passe ni le hash")
  void noSecretInResponse() throws Exception {
    MvcResult r = register("Camille", "camille@exemple.fr", "motdepasse-secret");
    String body = body(r);
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(body).doesNotContain("motdepasse-secret").doesNotContain("password").doesNotContain("$2y$");
  }

  @Test
  @DisplayName("SC-IDENT-002 — e-mail déjà utilisé (autre casse) → 409, aucun second compte")
  void duplicate() throws Exception {
    assertThat(register("Camille", "camille@exemple.fr", "motdepasse").getResponse().getStatus()).isEqualTo(201);
    MvcResult r = register("Autre", "CAMILLE@exemple.fr", "motdepasse");
    assertError(r, 409, "Un compte existe déjà avec cette adresse e-mail.");
    assertThat(accounts()).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM taskora.`user`")).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          name vide                 | {"name":"","email":"camille@exemple.fr","password":"motdepasse"}  | Le champ "name" est obligatoire.
          name d'espaces            | {"name":"   ","email":"camille@exemple.fr","password":"motdepasse"} | Le champ "name" est obligatoire.
          name absent               | {"email":"camille@exemple.fr","password":"motdepasse"}            | Le champ "name" est obligatoire.
          email absent              | {"name":"Camille","password":"motdepasse"}                         | Le champ "email" est obligatoire.
          email vide                | {"name":"Camille","email":" ","password":"motdepasse"}             | Le champ "email" est obligatoire.
          email invalide            | {"name":"Camille","email":"pas-un-email","password":"motdepasse"}  | Adresse e-mail invalide.
          password de 7             | {"name":"Camille","email":"camille@exemple.fr","password":"1234567"} | Le mot de passe doit contenir au moins 8 caractères.
          password absent           | {"name":"Camille","email":"camille@exemple.fr"}                    | Le mot de passe doit contenir au moins 8 caractères.
          ééééééé (7)               | {"name":"Camille","email":"camille@exemple.fr","password":"ééééééé"} | Le mot de passe doit contenir au moins 8 caractères.
          ordre name puis email     | {"name":"","email":"x","password":"1"}                             | Le champ "name" est obligatoire.
          ordre email puis password | {"name":"C","email":"x","password":"1"}                            | Adresse e-mail invalide.
          corps texte               | texte                                                              | JSON invalide.
          """)
  @DisplayName("SC-IDENT-003 — validation : message exact, rien n'est écrit")
  void validation(String label, String body, String message) throws Exception {
    assertError(postJson("/api/auth/register", body), 400, message);
    assertThat(accounts()).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-003 — 8 caractères exactement → 201")
  void eightAccepted() throws Exception {
    assertThat(register("Camille", "camille@exemple.fr", "12345678").getResponse().getStatus()).isEqualTo(201);
  }

  @Test
  @DisplayName("SC-IDENT-004 — nom de 150 caractères → compte créé, nom tronqué à 120")
  void longName() throws Exception {
    MvcResult r = register("n".repeat(150), "camille@exemple.fr", "motdepasse");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(json(r).get("user").get("name").asString()).hasSize(120);
    assertThat((String) query("SELECT name FROM identity.user_account").getFirst().get("name")).hasSize(120);
  }

  @Test
  @DisplayName("SC-IDENT-004 — e-mail de 182 caractères tronqué à 180 avant validation → 400 « Adresse e-mail invalide. »")
  void longEmailTruncatedThenInvalid() throws Exception {
    String email = "a".repeat(40) + "@" + "d".repeat(60) + "." + "d".repeat(60) + "." + "d".repeat(16) + ".fr";
    assertThat(email).hasSize(182);
    assertError(register("Camille", email, "motdepasse"), 400, "Adresse e-mail invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — mot de passe de 4096 octets → 201")
  void password4096() throws Exception {
    assertThat(register("Camille", "camille@exemple.fr", "a".repeat(4096)).getResponse().getStatus()).isEqualTo(201);
  }

  @ParameterizedTest
  @ValueSource(ints = {4097, 10_000})
  @DisplayName("SC-IDENT-028 E15 — mot de passe > 4096 octets → 400 (et non 500), aucun compte")
  void passwordTooLong(int bytes) throws Exception {
    assertError(register("Camille", "camille@exemple.fr", "a".repeat(bytes)), 400, TOO_LONG);
    assertThat(accounts()).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — limite en octets : 2049 × é (4098 octets) → 400")
  void passwordTooLongInBytes() throws Exception {
    assertError(register("Camille", "camille@exemple.fr", "é".repeat(2049)), 400, TOO_LONG);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "[1,2]", "\"texte\"", "42", "true", "null"})
  @DisplayName("SC-IDENT-029 E16 — JSON valide mais pas un objet → 400 « JSON invalide. »")
  void nonObject(String body) throws Exception {
    assertError(postJson("/api/auth/register", body), 400, "JSON invalide.");
    assertThat(accounts()).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-029 — {} → 400 « Le champ \"name\" est obligatoire. »")
  void emptyObject() throws Exception {
    assertError(postJson("/api/auth/register", "{}"), 400, "Le champ \"name\" est obligatoire.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\n"})
  @DisplayName("SC-IDENT-033 E20 — corps vide ou d'espaces → 400 « JSON invalide. »")
  void emptyBody(String body) throws Exception {
    assertError(postJson("/api/auth/register", body), 400, "JSON invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-033 E20 — sans Content-Type ni corps → 400 « JSON invalide. »")
  void noBodyNoContentType() throws Exception {
    MvcResult r = mvc.perform(post("/api/auth/register")).andReturn();
    assertError(r, 400, "JSON invalide.");
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource({
    "name,nombre", "name,booléen", "name,null", "name,tableau", "name,objet",
    "email,nombre", "email,booléen", "email,null", "email,tableau", "email,objet",
    "password,nombre", "password,booléen", "password,null", "password,tableau", "password,objet"
  })
  @DisplayName("SC-IDENT-030 E17 — champ non chaîne (null compris) → 400 « Le champ \"x\" doit être une chaîne de caractères. »")
  void nonStringField(String field, String type) throws Exception {
    Map<String, String> values =
        new java.util.LinkedHashMap<>(
            Map.of("name", "\"Camille\"", "email", "\"camille@exemple.fr\"", "password", "\"motdepasse\""));
    values.put(field, nonStringJson(type));
    String body =
        "{\"name\":" + values.get("name") + ",\"email\":" + values.get("email") + ",\"password\":" + values.get("password") + "}";
    assertError(postJson("/api/auth/register", body), 400, typeError(field));
    assertThat(accounts()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          type de name avant contenu d'email      | {"name":123,"email":"x","password":"1"}           | name
          type d'email avant contenu de name      | {"name":"","email":true,"password":"1"}           | email
          type de password avant contenu de name  | {"name":"","email":"x","password":12345678}       | password
          premier champ invalide : name           | {"name":1,"email":2,"password":3}                 | name
          premier champ invalide : email          | {"name":"C","email":2,"password":3}               | email
          """)
  @DisplayName("SC-IDENT-030 E17 — contrôle de type de tous les champs, dans l'ordre, AVANT les contrôles de contenu")
  void typeCheckedFirstInOrder(String label, String body, String field) throws Exception {
    assertError(postJson("/api/auth/register", body), 400, typeError(field));
  }

  @Test
  @DisplayName("SC-IDENT-030 E17 — clés inconnues ignorées quel que soit leur type")
  void unknownKeysIgnored() throws Exception {
    MvcResult r =
        postJson(
            "/api/auth/register",
            "{\"name\":\"Camille\",\"email\":\"camille@exemple.fr\",\"password\":\"motdepasse\",\"avatarColor\":1,\"x\":[]}");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
  }

  @Test
  @DisplayName("SC-IDENT-024 — corps illisible : 400 sans trace ni nom de classe")
  void noStackTrace() throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":".getBytes(StandardCharsets.UTF_8)))
            .andReturn();
    assertError(r, 400, "JSON invalide.");
    assertThat(body(r)).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("jackson");
  }
}
