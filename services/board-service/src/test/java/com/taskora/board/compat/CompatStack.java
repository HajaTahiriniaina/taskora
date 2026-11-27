package com.taskora.board.compat;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stack jetable {@code -p taskora-ci-board} pour les tests {@code @Tag("compat")} (profil Maven
 * compat) : Symfony (compat.symfony.url, 8081) et board-service (compat.board.url, 8083) sur la
 * MÊME base ; nginx (compat.web.url). Comptes {@code compat-<uuid>@exemple.test} créés par l'API
 * Symfony : jamais sur la stack de travail ni sur taskora-e2e. Secrets internes lus dans
 * l'environnement du job (jamais commités, jamais affichés).
 */
final class CompatStack {
  static final String SYMFONY = System.getProperty("compat.symfony.url", "http://localhost:8081");
  static final String BOARD = System.getProperty("compat.board.url", "http://localhost:8083");
  static final String WEB = System.getProperty("compat.web.url", "http://localhost:8080");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String PASSWORD = "motdepasse-compat-" + UUID.randomUUID();

  private CompatStack() {}

  /** Compte de test inscrit par Symfony ; rend son jeton (accepté par les deux amonts). */
  record Account(int id, String email, String name, String token) {}

  static String uniqueEmail(String prefix) {
    return prefix + "-" + UUID.randomUUID() + "@exemple.test";
  }

  static void requireBothUp() {
    for (String base : new String[] {SYMFONY, BOARD}) {
      Response r = given().baseUri(base).get("/api/boards");
      assertThat(r.statusCode())
          .as("%s doit répondre (401 attendu sans jeton)", base)
          .isEqualTo(401);
    }
  }

  static Account register(String name) {
    String email = uniqueEmail("compat");
    Response r =
        given()
            .baseUri(SYMFONY)
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + name
                    + "\",\"email\":\""
                    + email
                    + "\",\"password\":\""
                    + PASSWORD
                    + "\"}")
            .post("/api/auth/register");
    assertThat(r.statusCode()).as("inscription : %s", r.asString()).isEqualTo(201);
    JsonNode b = JSON.readTree(r.asString());
    return new Account(b.get("user").get("id").asInt(), email, name, b.get("token").asString());
  }

  static RequestSpecification as(String base, Account a) {
    RequestSpecification spec = given().baseUri(base).urlEncodingEnabled(false);
    return a == null ? spec : spec.header("Authorization", "Bearer " + a.token());
  }

  static Response send(String base, Account a, String method, String path, String body) {
    RequestSpecification spec = as(base, a);
    if (body != null) {
      spec = spec.contentType("application/json").body(body);
    }
    return spec.request(method, path);
  }

  static JsonNode json(Response r) {
    String s = r.asString();
    return s == null || s.isEmpty() ? null : JSON.readTree(s);
  }

  /** Secret d'un appelant interne (variable d'environnement du job), ou échec explicite. */
  static String secret(String variable) {
    String v = System.getenv(variable);
    assertThat(v).as("variable %s absente de l'environnement du job compat", variable).isNotBlank();
    return v;
  }
}
