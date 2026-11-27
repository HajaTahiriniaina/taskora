package com.taskora.identity.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.identity.support.ApiTestBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Ordre de travail phase 3 §5.1 (U12, board-migration.md §5.3) — URL et secret de consommation des
 * invitations configurables : {@code identity.invitations.claim-url} ({@code INVITATION_CLAIM_URL},
 * défaut {@code SYMFONY_INTERNAL_URL}) et {@code identity.invitations.claim-secret} ({@code
 * INVITATION_CLAIM_SECRET}, défaut {@code INTERNAL_API_SECRET}), utilisés ENSEMBLE par {@code
 * SymfonyInvitationClaimClient}. Deux cibles WireMock (Symfony « A », board-service « B ») qui
 * n'acceptent chacune que leur secret : un message envoyé à la mauvaise cible avec le mauvais
 * secret → 401 → {@code FAILED}. Secret de claim vide → refus de démarrer. Rien d'autre ne change.
 */
class InvitationClaimUrlConfigTest {
  private static final String PATH = "/internal/invitations/claim";

  /** Secret accepté par la cible A (Symfony) : celui d'ApiTestBase (INTERNAL_API_SECRET). */
  static final String SECRET_A = ApiTestBase.INTERNAL_SECRET;

  /** Secret accepté par la cible B (board-service) : INTERNAL_SECRET_IDENTITY_TO_BOARD. */
  static final String SECRET_B = "test-identity-to-board-secret-fedcba9876543210";

  static final WireMockServer TARGET_A = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer TARGET_B = new WireMockServer(wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    if (!TARGET_A.isRunning()) {
      TARGET_A.start();
    }
    if (!TARGET_B.isRunning()) {
      TARGET_B.start();
    }
  }

  @AfterAll
  static void stop() {
    TARGET_A.stop();
    TARGET_B.stop();
  }

  /** Chaque cible n'accepte que son secret : tout autre → 401. */
  static void stubTargets() {
    for (Object[] t : new Object[][] {{TARGET_A, SECRET_A}, {TARGET_B, SECRET_B}}) {
      WireMockServer server = (WireMockServer) t[0];
      if (!server.isRunning()) {
        server.start();
      }
      server.resetAll();
      server.stubFor(
          post(urlEqualTo(PATH))
              .atPriority(5)
              .willReturn(
                  aResponse()
                      .withStatus(401)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"error\":\"Accès interne refusé.\"}")));
      server.stubFor(
          post(urlEqualTo(PATH))
              .atPriority(1)
              .withHeader("X-Internal-Secret", equalTo((String) t[1]))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"claimed\":0,\"boardIds\":[]}")));
    }
  }

  static List<LoggedRequest> calls(WireMockServer server) {
    return server.findAll(postRequestedFor(urlEqualTo(PATH)));
  }

  /** Base commune : inscription, puis statut du premier message de claim. */
  abstract static class ClaimScenario extends ApiTestBase {
    @BeforeEach
    void targets() {
      stubTargets();
    }

    String registerAndClaimStatus() throws Exception {
      MvcResult r = register("Camille", "camille@exemple.fr", "motdepasse");
      assertThat(r.getResponse().getStatus()).as("inscription inchangée : 201").isEqualTo(201);
      List<Map<String, Object>> claims =
          query(
              "SELECT status FROM identity.outbox_message"
                  + " WHERE destination = 'symfony-invitations' ORDER BY id");
      assertThat(claims).isNotEmpty();
      return (String) claims.getFirst().get("status");
    }
  }

  @Nested
  @DisplayName("Défaut : sans INVITATION_CLAIM_URL ni INVITATION_CLAIM_SECRET")
  class Defaults extends ClaimScenario {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
      start();
      r.add("SYMFONY_INTERNAL_URL", TARGET_A::baseUrl);
      r.add("INTERNAL_API_SECRET", () -> SECRET_A);
      r.add("identity.symfony.internal-url", TARGET_A::baseUrl);
      r.add("identity.symfony.internal-secret", () -> SECRET_A);
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
        "§5.1 — défaut : URL = SYMFONY_INTERNAL_URL, secret = INTERNAL_API_SECRET → livré à Symfony (A)")
    void defaultsToSymfony() throws Exception {
      assertThat(registerAndClaimStatus()).isEqualTo("DELIVERED");
      assertThat(calls(TARGET_A)).hasSize(1);
      assertThat(calls(TARGET_A).getFirst().getHeader("X-Internal-Secret")).isEqualTo(SECRET_A);
      assertThat(calls(TARGET_B)).isEmpty();
    }
  }

  @Nested
  @DisplayName("identity.invitations.claim-url et claim-secret explicites")
  class ExplicitProperties extends ClaimScenario {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
      start();
      r.add("identity.symfony.internal-url", TARGET_A::baseUrl);
      r.add("identity.symfony.internal-secret", () -> SECRET_A);
      r.add("identity.invitations.claim-url", TARGET_B::baseUrl);
      r.add("identity.invitations.claim-secret", () -> SECRET_B);
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
        "§5.1 / U12 — claim-url et claim-secret utilisés ENSEMBLE → livré à board-service (B) avec son secret, rien vers A")
    void usedTogether() throws Exception {
      assertThat(registerAndClaimStatus()).isEqualTo("DELIVERED");
      assertThat(calls(TARGET_B)).hasSize(1);
      assertThat(calls(TARGET_B).getFirst().getHeader("X-Internal-Secret")).isEqualTo(SECRET_B);
      assertThat(calls(TARGET_A)).isEmpty();
    }
  }

  @Nested
  @DisplayName("Variables INVITATION_CLAIM_URL et INVITATION_CLAIM_SECRET (application.yml)")
  class EnvironmentVariables extends ClaimScenario {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
      start();
      r.add("SYMFONY_INTERNAL_URL", TARGET_A::baseUrl);
      r.add("INTERNAL_API_SECRET", () -> SECRET_A);
      r.add("identity.symfony.internal-url", TARGET_A::baseUrl);
      r.add("identity.symfony.internal-secret", () -> SECRET_A);
      r.add("INVITATION_CLAIM_URL", TARGET_B::baseUrl);
      r.add("INVITATION_CLAIM_SECRET", () -> SECRET_B);
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
        "§5.1 — INVITATION_CLAIM_URL / INVITATION_CLAIM_SECRET lues par application.yml → livré à B")
    void environmentMapping() throws Exception {
      assertThat(registerAndClaimStatus()).isEqualTo("DELIVERED");
      assertThat(calls(TARGET_B)).hasSize(1);
      assertThat(calls(TARGET_A)).isEmpty();
    }
  }

  @Nested
  @DisplayName("Mauvaise cible avec le mauvais secret")
  class WrongTargetWrongSecret extends ClaimScenario {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
      start();
      r.add("identity.symfony.internal-url", TARGET_A::baseUrl);
      r.add("identity.symfony.internal-secret", () -> SECRET_A);
      r.add("identity.invitations.claim-url", TARGET_B::baseUrl);
      r.add("identity.invitations.claim-secret", () -> SECRET_A);
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
        "§5.1 / U12 — board-service (B) reçoit le secret de Symfony → 401 → FAILED (non rejouable)")
    void wrongSecretFails() throws Exception {
      assertThat(registerAndClaimStatus()).isEqualTo("FAILED");
      assertThat(calls(TARGET_B)).hasSize(1);
      assertThat(calls(TARGET_A)).isEmpty();
    }
  }

  @Nested
  @DisplayName("Démarrage")
  @ExtendWith(OutputCaptureExtension.class)
  class Startup {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class PropertiesOnly {}

    private ApplicationContextRunner runner(String claimSecret) {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("identity.jwt.private-key-path", "/tmp/absent/private.pem");
      props.put("identity.jwt.public-key-path", "/tmp/absent/public.pem");
      props.put("identity.jwt.ttl", "604800");
      props.put("identity.legacy.schema", "taskora");
      props.put("identity.legacy.zone", "Europe/Paris");
      props.put("identity.symfony.internal-url", "http://api:80");
      props.put("identity.symfony.internal-secret", SECRET_A);
      props.put("identity.password.bcrypt-cost", "4");
      props.put("identity.outbox.relay.enabled", "false");
      props.put("identity.invitations.claim-url", "http://board:8080");
      props.put("identity.invitations.claim-secret", claimSecret);
      return new ApplicationContextRunner()
          .withUserConfiguration(PropertiesOnly.class)
          .withInitializer(context ->
              context
                  .getEnvironment()
                  .getPropertySources()
                  .addFirst(new MapPropertySource("claim", props)));
    }

    @ParameterizedTest(name = "claim-secret = \"{0}\"")
    @ValueSource(strings = {"", " ", "\t", " \t \n "})
    @DisplayName(
        "§5.1 — secret de claim vide ou blanc → démarrage refusé, message nommant INVITATION_CLAIM_SECRET, sans valeur")
    void blankClaimSecretRefused(String secret, CapturedOutput output) {
      runner(secret)
          .run(context -> {
            assertThat(context).hasFailed();
            List<String> messages = new ArrayList<>();
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
              messages.add(String.valueOf(t.getMessage()));
            }
            assertThat(String.join("\n", messages))
                .contains("INVITATION_CLAIM_SECRET")
                .doesNotContain(SECRET_A);
          });
      assertThat(output.getAll()).doesNotContain(SECRET_A);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("§5.1 — secret de claim non vide → démarrage ; aucun secret dans toString")
    void claimSecretBound() {
      runner(SECRET_B)
          .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdentityProperties.class).toString())
                .doesNotContain(SECRET_B)
                .doesNotContain(SECRET_A);
          });
    }
  }
}
