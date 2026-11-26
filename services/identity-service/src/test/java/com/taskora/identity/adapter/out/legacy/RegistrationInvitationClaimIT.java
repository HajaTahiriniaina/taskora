package com.taskora.identity.adapter.out.legacy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.support.ApiTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * SC-IDENT-005/006 (A10) : client de POST /internal/invitations/claim, livraison synchrone puis
 * outbox.
 */
class RegistrationInvitationClaimIT extends ApiTestBase {
  private static final String PATH = "/internal/invitations/claim";
  static final WireMockServer SYMFONY = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    SYMFONY.start();
  }

  @Autowired DeliverPendingMessagesUseCase relay;

  @DynamicPropertySource
  static void symfony(DynamicPropertyRegistry r) {
    r.add("identity.symfony.internal-url", SYMFONY::baseUrl);
  }

  @AfterAll
  static void stop() {
    SYMFONY.stop();
  }

  @BeforeEach
  void resetSymfony() {
    SYMFONY.resetAll();
  }

  private static void symfonyAnswers(int status) {
    SYMFONY.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        status == 200 ? "{\"claimed\":0,\"boardIds\":[]}" : "{\"error\":\"x\"}")));
  }

  private static List<Map<String, Object>> claims() {
    return query(
        "SELECT * FROM identity.outbox_message WHERE destination = 'symfony-invitations' ORDER BY id");
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 — Symfony 200 : appel AVANT la réponse 201, corps InvitationClaimRequest exact, X-Internal-Secret, claim DELIVERED")
  void deliveredSynchronously() throws Exception {
    symfonyAnswers(200);
    MvcResult r = register("Camille", " Camille@Exemple.FR ", "motdepasse");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    int userId = json(r).get("user").get("id").asInt();

    List<LoggedRequest> calls = SYMFONY.findAll(postRequestedFor(urlEqualTo(PATH)));
    assertThat(calls).hasSize(1);
    LoggedRequest call = calls.getFirst();
    assertThat(call.getHeader("X-Internal-Secret")).isEqualTo(INTERNAL_SECRET);
    assertThat(call.getHeader("Content-Type")).contains("application/json");
    JsonNode body = JSON.readTree(call.getBodyAsString());
    assertThat(new java.util.ArrayList<>(body.propertyNames()))
        .containsExactlyInAnyOrder("eventId", "userId", "email");
    assertThat(UUID.fromString(body.get("eventId").asString()).version()).isEqualTo(4);
    assertThat(body.get("userId").isIntegralNumber()).isTrue();
    assertThat(body.get("userId").asInt()).isEqualTo(userId);
    assertThat(body.get("email").asString()).isEqualTo("camille@exemple.fr");

    List<Map<String, Object>> rows = claims();
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().get("status")).isEqualTo("DELIVERED");
    assertThat(rows.getFirst().get("event_id")).isEqualTo(body.get("eventId").asString());
    assertThat(rows.get(1).get("status")).isEqualTo("PENDING");
    assertThat(rows.getFirst().get("type")).isEqualTo("InvitationClaimRequested");
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 — la projection est visible par Symfony au moment de l'appel (appel après validation)")
  void projectionCommittedBeforeCall() throws Exception {
    SYMFONY.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"claimed\":0,\"boardIds\":[]}")));
    final long[] seenByCall = {-1};
    SYMFONY.addMockServiceRequestListener((req, resp) ->
        seenByCall[0] =
            count("SELECT COUNT(*) FROM taskora.`user` WHERE email = 'camille@exemple.fr'"));
    register("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(seenByCall[0]).isEqualTo(1);
  }

  @ParameterizedTest(name = "Symfony {0} → {1}")
  @CsvSource({
    "500,PENDING",
    "503,PENDING",
    "404,PENDING",
    "401,FAILED",
    "400,FAILED",
    "409,FAILED"
  })
  @DisplayName(
      "SC-IDENT-005 — réponse de Symfony : 201 quand même ; 5xx/404 rejouables, 401/400/409 FAILED")
  void outcomes(int status, String expected) throws Exception {
    symfonyAnswers(status);
    MvcResult r = register("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(claims().getFirst().get("status")).isEqualTo(expected);
    if ("PENDING".equals(expected)) {
      assertThat(((Number) claims().getFirst().get("attempts")).intValue()).isEqualTo(1);
      assertThat((String) claims().getFirst().get("last_error")).doesNotContain("camille");
    }
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 — délai de lecture > 3 s → 201 sans attendre indéfiniment, message rejouable")
  void timeout() throws Exception {
    SYMFONY.stubFor(
        post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
    long t0 = System.nanoTime();
    MvcResult r = register("Camille", "camille@exemple.fr", "motdepasse");
    long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(ms).isLessThan(4_900);
    assertThat(claims().getFirst().get("status")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("SC-IDENT-005 — échec puis rejeu par le relais après le backoff (5 s) → DELIVERED")
  void replayedByRelay() throws Exception {
    symfonyAnswers(500);
    register("Camille", "camille@exemple.fr", "motdepasse");
    SYMFONY.resetAll();
    symfonyAnswers(200);
    relay.deliverDue(20);
    assertThat(SYMFONY.findAll(postRequestedFor(urlEqualTo(PATH))))
        .as("pas avant l'échéance")
        .isEmpty();
    CLOCK.advance(Duration.ofSeconds(5));
    relay.deliverDue(20);
    assertThat(SYMFONY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    assertThat(claims().getFirst().get("status")).isEqualTo("DELIVERED");
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 — claim de rattrapage envoyé à +60 s (même userId et e-mail, autre eventId)")
  void catchUpAfter60s() throws Exception {
    symfonyAnswers(200);
    register("Camille", "camille@exemple.fr", "motdepasse");
    CLOCK.advance(Duration.ofSeconds(59));
    relay.deliverDue(20);
    assertThat(SYMFONY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    CLOCK.advance(Duration.ofSeconds(1));
    relay.deliverDue(20);
    List<LoggedRequest> calls = SYMFONY.findAll(postRequestedFor(urlEqualTo(PATH)));
    assertThat(calls).hasSize(2);
    JsonNode a = JSON.readTree(calls.getFirst().getBodyAsString());
    JsonNode b = JSON.readTree(calls.get(1).getBodyAsString());
    assertThat(b.get("userId")).isEqualTo(a.get("userId"));
    assertThat(b.get("email")).isEqualTo(a.get("email"));
    assertThat(b.get("eventId")).isNotEqualTo(a.get("eventId"));
    assertThat(claims()).allSatisfy(c -> assertThat(c.get("status")).isEqualTo("DELIVERED"));
    SYMFONY.verify(
        2,
        postRequestedFor(urlEqualTo(PATH))
            .withHeader("X-Internal-Secret", equalTo(INTERNAL_SECRET)));
  }

  @Test
  @DisplayName("SC-IDENT-005 — Symfony injoignable : 201, inscription utilisable immédiatement")
  void symfonyDown() throws Exception {
    SYMFONY.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
    MvcResult r = register("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(login("camille@exemple.fr", "motdepasse").getResponse().getStatus()).isEqualTo(200);
  }
}
