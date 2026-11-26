package com.taskora.identity.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.taskora.identity.IdentityServiceApplication;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base des tests d'API : application complète (MockMvc), MariaDB Testcontainers, paire de clés de
 * test, horloge réglable, bcrypt coût 4, relais d'outbox désactivé, Symfony injoignable (les claims
 * d'invitation restent en attente ; voir RegistrationInvitationClaimIT pour la livraison).
 */
@SpringBootTest(
    classes = IdentityServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@TestPropertySource(
    properties = {
      // Valeurs par défaut : un @DynamicPropertySource de sous-classe les remplace (priorité
      // supérieure).
      "identity.password.bcrypt-cost=4",
      // Symfony injoignable (port fermé) ; RegistrationInvitationClaimIT et OutboxRelayIT pointent
      // sur WireMock.
      "identity.symfony.internal-url=http://127.0.0.1:9"
    })
public abstract class ApiTestBase {
  protected static final MutableClock CLOCK = TestClockConfig.CLOCK;
  protected static final TestKeys KEYS = TestKeys.shared();
  protected static final TokenFactory TOKENS = new TokenFactory(KEYS, CLOCK);
  public static final String INTERNAL_SECRET = "test-internal-secret-0123456789abcdef";

  @Autowired protected MockMvc mvc;

  @DynamicPropertySource
  static void identityProperties(DynamicPropertyRegistry registry) {
    MariaDbContainerSupport.register(registry);
    registry.add("identity.jwt.private-key-path", () -> KEYS.privatePem().toString());
    registry.add("identity.jwt.public-key-path", () -> KEYS.publicPem().toString());
    registry.add("identity.jwt.passphrase", KEYS::passphrase);
    registry.add("identity.jwt.ttl", () -> "604800");
    registry.add("identity.outbox.relay.enabled", () -> "false");
    registry.add("identity.symfony.internal-secret", () -> INTERNAL_SECRET);
  }

  @AfterAll
  static void closeRootPool() {
    MariaDbContainerSupport.closeRootPool();
  }

  @BeforeEach
  void resetDatabaseAndClock() {
    MariaDbContainerSupport.reset();
    CLOCK.set(Instant.ofEpochSecond(Instant.now().getEpochSecond()));
  }

  // ------------------------------------------------------------------ requêtes

  protected MvcResult postJson(String path, String body) throws Exception {
    return mvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
        .andReturn();
  }

  protected MvcResult register(String name, String email, String password) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("email", email);
    body.put("password", password);
    return postJson("/api/auth/register", Json.write(body));
  }

  protected MvcResult login(String email, String password) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", email);
    body.put("password", password);
    return postJson("/api/auth/login", Json.write(body));
  }

  protected MvcResult me(String token) throws Exception {
    return mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andReturn();
  }

  protected MvcResult patchMe(String token, String body) throws Exception {
    return mvc.perform(
            patch("/api/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
        .andReturn();
  }

  /** Inscrit un compte via l'API et renvoie son jeton. */
  protected String registeredToken(String name, String email, String password) throws Exception {
    MvcResult r = register(name, email, password);
    if (r.getResponse().getStatus() != 201) {
      throw new AssertionError(
          "inscription de préparation refusée : " + r.getResponse().getStatus() + " " + body(r));
    }
    return com.jayway.jsonpath.JsonPath.read(body(r), "$.token");
  }

  protected static String body(MvcResult r) {
    try {
      return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------ assertions de réponse

  protected static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  protected static tools.jackson.databind.JsonNode json(MvcResult r) {
    return JSON.readTree(body(r));
  }

  /** Statut, corps exact {"error": message} et conformité au contrat. */
  protected static void assertError(MvcResult r, int status, String message) {
    org.assertj.core.api.Assertions.assertThat(r.getResponse().getStatus())
        .as("statut ; corps = %s", body(r))
        .isEqualTo(status);
    org.assertj.core.api.Assertions.assertThat(json(r))
        .isEqualTo(JSON.readTree(Json.write(Map.of("error", message))));
    OpenApiValidation.assertResponseConforms(r);
  }

  /** 401 Lexik : {"code":401,"message":…} + WWW-Authenticate: Bearer, conforme au contrat. */
  protected static void assertAuthError(MvcResult r, String message) {
    org.assertj.core.api.Assertions.assertThat(r.getResponse().getStatus())
        .as("statut ; corps = %s", body(r))
        .isEqualTo(401);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("code", 401);
    expected.put("message", message);
    org.assertj.core.api.Assertions.assertThat(json(r))
        .isEqualTo(JSON.readTree(Json.write(expected)));
    org.assertj.core.api.Assertions.assertThat(r.getResponse().getHeader("WWW-Authenticate"))
        .isEqualTo("Bearer");
    OpenApiValidation.assertResponseConforms(r);
  }

  /** Corps utilisateur exact (4 clés, rien d'autre). */
  protected static void assertUserJson(
      tools.jackson.databind.JsonNode user, String name, String email, String avatarColor) {
    org.assertj.core.api.Assertions.assertThat(new java.util.ArrayList<>(user.propertyNames()))
        .containsExactlyInAnyOrder("id", "name", "email", "avatarColor");
    org.assertj.core.api.Assertions.assertThat(user.get("id").isIntegralNumber()).isTrue();
    org.assertj.core.api.Assertions.assertThat(user.get("name").asString()).isEqualTo(name);
    org.assertj.core.api.Assertions.assertThat(user.get("email").asString()).isEqualTo(email);
    if (avatarColor != null) {
      org.assertj.core.api.Assertions.assertThat(user.get("avatarColor").asString())
          .isEqualTo(avatarColor);
    }
  }

  protected static String nonStringJson(String type) {
    return switch (type) {
      case "nombre" -> "123";
      case "booléen" -> "true";
      case "null" -> "null";
      case "tableau" -> "[\"a\"]";
      case "objet" -> "{\"a\":\"b\"}";
      default -> throw new IllegalArgumentException(type);
    };
  }

  protected static String typeError(String field) {
    return "Le champ \"" + field + "\" doit être une chaîne de caractères.";
  }

  // ------------------------------------------------------------------ lecture directe (root)

  protected static List<Map<String, Object>> query(String sql, Object... args) {
    try (Connection c = MariaDbContainerSupport.rootConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= n; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  protected static long count(String sql, Object... args) {
    return ((Number) query(sql, args).getFirst().values().iterator().next()).longValue();
  }

  protected static String storedHash(String email) {
    return (String)
        query("SELECT password_hash FROM identity.user_account WHERE email = ?", email)
            .getFirst()
            .get("password_hash");
  }
}
