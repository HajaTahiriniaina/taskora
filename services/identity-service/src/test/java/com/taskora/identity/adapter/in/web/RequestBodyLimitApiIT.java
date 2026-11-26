package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.OpenApiValidation;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * SC-IDENT-035 (ADR A18) : register, login et PATCH /me lisent au plus 65 536 octets ; au-delà →
 * 413 {"error":"Corps de requête trop volumineux."}, avec Content-Length comme en chunked. Serveur
 * réel (port aléatoire) : MockMvc ne sait pas émettre de corps en Transfer-Encoding: chunked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyLimitApiIT extends ApiTestBase {
  private static final int LIMIT = 65_536;
  private static final String TOO_LARGE = "Corps de requête trop volumineux.";

  @LocalServerPort int port;

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private String token;

  @BeforeEach
  void account() throws Exception {
    token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
  }

  /** Corps JSON valide dont la clé inconnue "pad" porte la taille au nombre d'octets demandé. */
  private static byte[] padded(String prefixFields, int totalBytes) {
    String head = "{" + prefixFields + ",\"pad\":\"";
    String tail = "\"}";
    int pad = totalBytes - head.getBytes(StandardCharsets.UTF_8).length - tail.length();
    byte[] body = (head + "x".repeat(pad) + tail).getBytes(StandardCharsets.UTF_8);
    assertThat(body).hasSize(totalBytes);
    return body;
  }

  private static byte[] bodyFor(String route, boolean valid, int size) {
    return switch (route) {
      case "register" ->
          padded(
              valid
                  ? "\"name\":\"Nouveau\",\"email\":\"nouveau@exemple.fr\",\"password\":\"motdepasse\""
                  : "\"name\":\"\",\"email\":\"nouveau@exemple.fr\",\"password\":\"motdepasse\"",
              size);
      case "login" ->
          padded(
              valid
                  ? "\"email\":\"camille@exemple.fr\",\"password\":\"ancienmdp1\""
                  : "\"email\":\"\",\"password\":\"ancienmdp1\"",
              size);
      case "me" -> padded(valid ? "\"name\":\"Pirate\"" : "\"name\":\"\"", size);
      default -> throw new IllegalArgumentException(route);
    };
  }

  private static String path(String route) {
    return switch (route) {
      case "register" -> "/api/auth/register";
      case "login" -> "/api/auth/login";
      default -> "/api/me";
    };
  }

  private static String method(String route) {
    return "me".equals(route) ? "PATCH" : "POST";
  }

  private HttpResponse<String> send(String route, byte[] body, boolean chunked, boolean withToken)
      throws Exception {
    HttpRequest.BodyPublisher publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
            : HttpRequest.BodyPublishers.ofByteArray(body);
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(route)))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(method(route), publisher);
    if (withToken) {
      b.header("Authorization", "Bearer " + token);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void assertJsonError(HttpResponse<String> r, int status, String message) {
    assertThat(r.statusCode()).as("statut ; corps = %s", r.body()).isEqualTo(status);
    JsonNode json = JSON.readTree(r.body());
    assertThat(json)
        .isEqualTo(
            JSON.readTree(com.taskora.identity.support.Json.write(Map.of("error", message))));
  }

  private void assertConforms(String route, HttpResponse<String> r) {
    OpenApiValidation.assertResponseConforms(
        method(route),
        path(route),
        r.statusCode(),
        r.headers().firstValue("Content-Type").orElse(null),
        r.body());
  }

  private static void assertNothingWritten() {
    assertThat(count("SELECT COUNT(*) FROM identity.user_account")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM taskora.`user`")).isEqualTo(1);
    assertThat(query("SELECT name FROM identity.user_account").getFirst().get("name"))
        .isEqualTo("Camille");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"register", "login", "me"})
  @DisplayName(
      "SC-IDENT-035 — 65 537 octets avec Content-Length → 413 « Corps de requête trop volumineux. », rien n'est écrit")
  void overLimitWithContentLength(String route) throws Exception {
    HttpResponse<String> r = send(route, bodyFor(route, true, LIMIT + 1), false, true);
    assertJsonError(r, 413, TOO_LARGE);
    assertConforms(route, r);
    assertNothingWritten();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"register", "login", "me"})
  @DisplayName("SC-IDENT-035 — 65 537 octets en Transfer-Encoding: chunked → 413, rien n'est écrit")
  void overLimitChunked(String route) throws Exception {
    HttpResponse<String> r = send(route, bodyFor(route, true, LIMIT + 1), true, true);
    assertJsonError(r, 413, TOO_LARGE);
    assertConforms(route, r);
    assertNothingWritten();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"register", "login", "me"})
  @DisplayName("SC-IDENT-035 — 1 Mio avec Content-Length → 413")
  void farOverLimit(String route) throws Exception {
    HttpResponse<String> r = send(route, bodyFor(route, true, 1_048_576), false, true);
    assertJsonError(r, 413, TOO_LARGE);
    assertNothingWritten();
  }

  @Test
  @DisplayName(
      "SC-IDENT-035 — register : 65 536 octets exactement → traité normalement (400 name obligatoire)")
  void registerAtLimit() throws Exception {
    for (boolean chunked : new boolean[] {false, true}) {
      HttpResponse<String> r = send("register", bodyFor("register", false, LIMIT), chunked, false);
      assertJsonError(r, 400, "Le champ \"name\" est obligatoire.");
      assertConforms("register", r);
    }
    assertNothingWritten();
  }

  @Test
  @DisplayName(
      "SC-IDENT-035 — login : 65 536 octets exactement → traité normalement (400 email obligatoire)")
  void loginAtLimit() throws Exception {
    for (boolean chunked : new boolean[] {false, true}) {
      HttpResponse<String> r = send("login", bodyFor("login", false, LIMIT), chunked, false);
      assertJsonError(r, 400, "Le champ \"email\" est obligatoire.");
      assertConforms("login", r);
    }
  }

  @Test
  @DisplayName(
      "SC-IDENT-035 — PATCH /me : 65 536 octets exactement → traité normalement (400 name obligatoire)")
  void meAtLimit() throws Exception {
    for (boolean chunked : new boolean[] {false, true}) {
      HttpResponse<String> r = send("me", bodyFor("me", false, LIMIT), chunked, true);
      assertJsonError(r, 400, "Le champ \"name\" est obligatoire.");
      assertConforms("me", r);
    }
    assertNothingWritten();
  }

  @Test
  @DisplayName(
      "SC-IDENT-035 — register : 65 536 octets valides → 201 (la limite n'est pas atteinte)")
  void registerValidAtLimit() throws Exception {
    HttpResponse<String> r = send("register", bodyFor("register", true, LIMIT), false, false);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
  }

  @ParameterizedTest(name = "chunked = {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "SC-IDENT-035 — PATCH /me sans jeton et corps trop gros → 401 d'abord (authentification avant la taille)")
  void unauthenticatedFirst(boolean chunked) throws Exception {
    HttpResponse<String> r = send("me", bodyFor("me", true, LIMIT + 1), chunked, false);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
    assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    assertThat(JSON.readTree(r.body()).get("message").asString()).isEqualTo("JWT Token not found");
    assertNothingWritten();
  }
}
