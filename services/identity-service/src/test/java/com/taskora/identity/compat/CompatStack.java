package com.taskora.identity.compat;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.UUID;

/**
 * Accès à la stack compose pour les tests @Tag("compat") (profil Maven compat). URL par propriétés
 * système compat.symfony.url, compat.identity.url, compat.web.url. Ces tests créent des comptes
 * compat-&lt;uuid&gt;@exemple.test : à lancer uniquement sur une stack jetable.
 */
final class CompatStack {
  static final String SYMFONY = System.getProperty("compat.symfony.url", "http://localhost:8081");
  static final String IDENTITY = System.getProperty("compat.identity.url", "http://localhost:8082");

  private CompatStack() {}

  static String uniqueEmail() {
    return "compat-" + UUID.randomUUID() + "@exemple.test";
  }

  /**
   * Vérifie qu'identity répond AVANT toute écriture sur Symfony : sans identity-service, aucun
   * compte de test n'est créé dans la base de la stack.
   */
  static void requireIdentityUp() {
    Response r =
        given()
            .baseUri(IDENTITY)
            .contentType("application/json")
            .body("{}")
            .post("/api/auth/login");
    assertThat(r.statusCode())
        .as("identity-service doit répondre sur %s", IDENTITY)
        .isLessThan(500);
  }

  static Response post(String base, String path, String body) {
    return given().baseUri(base).contentType("application/json").body(body).post(path);
  }

  static Response post(String base, String path, String body, String token) {
    return given()
        .baseUri(base)
        .contentType("application/json")
        .header("Authorization", "Bearer " + token)
        .body(body)
        .post(path);
  }

  static Response patch(String base, String path, String body, String token) {
    return given()
        .baseUri(base)
        .contentType("application/json")
        .header("Authorization", "Bearer " + token)
        .body(body)
        .patch(path);
  }

  static Response get(String base, String path, String token) {
    return given().baseUri(base).header("Authorization", "Bearer " + token).get(path);
  }

  static String register(String base, String email, String password) {
    Response r =
        post(
            base,
            "/api/auth/register",
            "{\"name\":\"Compat\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    assertThat(r.statusCode()).as("%s : %s", base, r.asString()).isEqualTo(201);
    return r.jsonPath().getString("token");
  }
}
