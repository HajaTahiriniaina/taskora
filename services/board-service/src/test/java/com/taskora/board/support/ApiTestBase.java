package com.taskora.board.support;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.BoardServiceApplication;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base des tests d'intégration et d'API : board-service complet sur un port aléatoire (vrais
 * en-têtes HTTP), MariaDB Testcontainers (schéma taskora généré + script d'init livré, compte
 * {@code board}), jeu de référence rechargé avant chaque test, horloge fixe ({@link Fixture#NOW}),
 * Symfony simulé par WireMock, relais planifié de l'outbox désactivé (les tests appellent le cas
 * d'utilisation). Toute réponse {@code /api/**} est validée contre board-service.yaml.
 *
 * <p>Propriétés attendues de board-service ({@code BoardProperties}) : {@code
 * board.jwt.public-key-path}, {@code board.internal.identity-to-board-secret}, {@code
 * board.internal.board-to-symfony-secret}, {@code board.symfony.internal-url}, {@code
 * board.legacy.schema}, {@code board.legacy.zone}, {@code board.outbox.relay.enabled}, {@code
 * board.routing.marker}, {@code board.purge.enabled}.
 */
@SpringBootTest(
    classes = BoardServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
public abstract class ApiTestBase {
  protected static final MutableClock CLOCK = TestClockConfig.CLOCK;
  protected static final TokenVectors TOKENS = TokenVectors.shared();
  public static final String IDENTITY_TO_BOARD_SECRET = "test-identity-to-board-" + hex();
  public static final String BOARD_TO_SYMFONY_SECRET = "test-board-to-symfony-" + hex();
  protected static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @LocalServerPort protected int port;
  @Autowired protected ApplicationContext context;

  private static String hex() {
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  @DynamicPropertySource
  static void boardProperties(DynamicPropertyRegistry r) {
    BoardDatabase.register(r);
    r.add("board.jwt.public-key-path", () -> TOKENS.publicPem().toString());
    r.add("board.internal.identity-to-board-secret", () -> IDENTITY_TO_BOARD_SECRET);
    r.add("board.internal.board-to-symfony-secret", () -> BOARD_TO_SYMFONY_SECRET);
    r.add("board.symfony.internal-url", SymfonyStub::baseUrl);
    r.add("board.legacy.schema", () -> "taskora");
    r.add("board.legacy.zone", () -> "Europe/Paris");
    r.add("board.outbox.relay.enabled", () -> "false");
    r.add("board.routing.marker", () -> "false");
    r.add("board.purge.enabled", () -> "false");
  }

  @AfterAll
  static void closeRootPool() {
    BoardDatabase.closeRootPool();
  }

  @BeforeEach
  void resetWorld() {
    BoardDatabase.loadReference();
    CLOCK.set(Fixture.NOW);
    SymfonyStub.reset();
  }

  // ------------------------------------------------------------------ jetons

  protected static String alice() {
    return TOKENS.identity(Fixture.ALICE_EMAIL, Fixture.ALICE);
  }

  protected static String bruno() {
    return TOKENS.identity(Fixture.BRUNO_EMAIL, Fixture.BRUNO);
  }

  /** Jeton Symfony (sans uid) : les deux émetteurs sont acceptés (SC-BOARD-020). */
  protected static String carla() {
    return TOKENS.symfony(Fixture.CARLA_EMAIL);
  }

  protected static String marc() {
    return TOKENS.identity(Fixture.MARC_EMAIL, Fixture.MARC);
  }

  protected static String nadia() {
    return TOKENS.identity(Fixture.NADIA_EMAIL, Fixture.NADIA);
  }

  protected static String eve() {
    return TOKENS.identity(Fixture.EVE_EMAIL, Fixture.EVE);
  }

  // ------------------------------------------------------------------ requêtes (RestAssured)

  /** Requête /api/** authentifiée, réponse validée contre board-service.yaml. */
  protected RequestSpecification api(String token) {
    RequestSpecification spec =
        given()
            .baseUri("http://localhost")
            .port(port)
            .filter(OpenApiContracts.publicApi())
            .urlEncodingEnabled(false);
    return token == null ? spec : spec.header("Authorization", "Bearer " + token);
  }

  /** Requête /internal/**, réponse validée contre board-service.internal.yaml. */
  protected RequestSpecification internal() {
    return given()
        .baseUri("http://localhost")
        .port(port)
        .filter(OpenApiContracts.internalApi())
        .urlEncodingEnabled(false);
  }

  protected Response get(String token, String path) {
    return api(token).get(path);
  }

  protected Response delete(String token, String path) {
    return api(token).delete(path);
  }

  /** Corps JSON brut (null = sans corps). */
  protected Response send(String method, String token, String path, String body) {
    RequestSpecification spec = api(token);
    if (body != null) {
      spec = spec.contentType("application/json").body(body.getBytes(StandardCharsets.UTF_8));
    }
    return spec.request(method, path);
  }

  protected Response post(String token, String path, String body) {
    return send("POST", token, path, body);
  }

  protected Response patch(String token, String path, String body) {
    return send("PATCH", token, path, body);
  }

  protected Response claim(String secret, String body) {
    RequestSpecification spec = internal().contentType("application/json").body(body);
    if (secret != null) {
      spec = spec.header("X-Internal-Secret", secret);
    }
    return spec.post("/internal/invitations/claim");
  }

  /** Client HTTP brut, sans validation de contrat (tests de concurrence). */
  protected RawResponse raw(String method, String token, String path, String body) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    try {
      HttpResponse<String> r =
          HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new RawResponse(r.statusCode(), r.body());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Réponse brute. */
  public record RawResponse(int status, String body) {}

  // ------------------------------------------------------------------ assertions

  protected static JsonNode json(Response r) {
    return JSON.readTree(r.asString());
  }

  protected static JsonNode json(String body) {
    return JSON.readTree(body);
  }

  protected static void assertStatus(Response r, int status) {
    assertThat(r.statusCode()).as("statut ; corps = %s", r.asString()).isEqualTo(status);
  }

  /** Statut et corps exact {"error": message}. */
  protected static void assertError(Response r, int status, String message) {
    assertStatus(r, status);
    assertThat(r.getContentType()).startsWith("application/json");
    assertThat(json(r)).isEqualTo(JSON.readTree(Json.write(Map.of("error", message))));
  }

  /** 401 Lexik : {"code":401,"message":…} + WWW-Authenticate: Bearer. */
  protected static void assertAuthError(Response r, String message) {
    assertStatus(r, 401);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("code", 401);
    expected.put("message", message);
    assertThat(json(r)).isEqualTo(JSON.readTree(Json.write(expected)));
    assertThat(r.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
  }

  /**
   * En-têtes d'une réponse, sans ceux qui varient d'une requête à l'autre quel que soit le chemin
   * ({@code Date}).
   */
  protected static Map<String, List<String>> headers(Response r) {
    Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    r.getHeaders()
        .forEach(h -> {
          if (!"Date".equalsIgnoreCase(h.getName())) {
            out.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
          }
        });
    return out;
  }

  /** Statut, corps et en-têtes identiques (SC-BOARD-021). */
  protected static void assertIndistinguishable(Response a, Response b, String what) {
    assertThat(a.statusCode()).as("%s : statut", what).isEqualTo(b.statusCode());
    assertThat(a.asString()).as("%s : corps", what).isEqualTo(b.asString());
    assertThat(headers(a)).as("%s : en-têtes", what).isEqualTo(headers(b));
  }

  /** Date ATOM avec décalage, secondes entières (B-ADR-11). */
  protected static void assertAtom(JsonNode date, String expected) {
    assertThat(date.asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}")
        .isEqualTo(expected);
  }

  // ------------------------------------------------------------------ base et beans

  protected static List<Map<String, Object>> query(String sql, Object... args) {
    return BoardDatabase.query(sql, args);
  }

  protected static long count(String sql, Object... args) {
    return BoardDatabase.count(sql, args);
  }

  protected static String scalar(String sql, Object... args) {
    return BoardDatabase.scalar(sql, args);
  }

  /** Lignes de l'outbox (vide si la table n'existe pas encore). */
  protected static List<Map<String, Object>> outbox() {
    try {
      return query("SELECT * FROM board.outbox_message ORDER BY id");
    } catch (IllegalStateException e) {
      throw new AssertionError("board.outbox_message absente (Flyway V1, BackCodeur)", e);
    }
  }

  protected static List<Map<String, Object>> outbox(String destination) {
    return outbox().stream().filter(m -> destination.equals(m.get("destination"))).toList();
  }

  /** Bean de board-service ; son absence est un échec explicite (code pas encore écrit). */
  protected <T> T bean(Class<T> type) {
    T bean = context.getBeanProvider(type).getIfAvailable();
    assertThat(bean)
        .as("bean %s absent du contexte (BackCodeur)", type.getSimpleName())
        .isNotNull();
    return bean;
  }

  protected static String path(String template, Object... args) {
    String p = template;
    for (Object a : args) {
      p = p.replaceFirst("\\{[^}]+}", String.valueOf(a));
    }
    return p;
  }
}
