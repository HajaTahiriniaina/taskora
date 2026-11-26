package com.taskora.identity.adapter.out.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.support.ApiTestBase;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** A11 : outbox transactionnelle, SKIP LOCKED, charges conformes au contrat interne. */
class OutboxRelayIT extends ApiTestBase {
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

  @Test
  @DisplayName(
      "SC-IDENT-005 A11 — deux relais concurrents (SKIP LOCKED) ne livrent jamais deux fois le même message")
  void skipLocked() throws Exception {
    SYMFONY.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 6; i++) {
      registeredToken("Compte " + i, "relais-" + i + "@exemple.test", "motdepasse");
    }
    SYMFONY.resetAll();
    SYMFONY.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay(300)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"claimed\":0,\"boardIds\":[]}")));
    CLOCK.advance(Duration.ofSeconds(61)); // les 12 claims (immédiats et de rattrapage) sont dus
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> f = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      f.add(
          pool.submit(() -> {
            go.await();
            return relay.deliverDue(20);
          }));
    }
    go.countDown();
    for (Future<Integer> x : f) {
      x.get();
    }
    pool.shutdown();
    Map<String, Integer> perEvent = new HashMap<>();
    SYMFONY
        .findAll(postRequestedFor(urlEqualTo(PATH)))
        .forEach(req ->
            perEvent.merge(
                JSON.readTree(req.getBodyAsString()).get("eventId").asString(), 1, Integer::sum));
    assertThat(perEvent).hasSize(12);
    assertThat(perEvent.values()).allMatch(n -> n == 1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM identity.outbox_message WHERE destination='symfony-invitations' AND status='DELIVERED'"))
        .isEqualTo(12);
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 A11 — UserRegistered : HELD, destination broker, charge conforme au schéma UserRegistered v1")
  void userRegisteredPayload() throws Exception {
    String token = registeredToken("Camille Martin", "camille@exemple.fr", "motdepasse");
    List<Map<String, Object>> rows =
        query("SELECT * FROM identity.outbox_message WHERE type = 'UserRegistered'");
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.getFirst();
    assertThat(row.get("status")).isEqualTo("HELD");
    assertThat(row.get("destination")).isEqualTo("broker");
    JsonNode p = JSON.readTree((String) row.get("payload"));
    assertThat(new ArrayList<>(p.propertyNames()))
        .containsExactlyInAnyOrder(
            "eventId", "type", "version", "occurredAt", "userId", "email", "name", "avatarColor");
    assertEnvelope(p, "UserRegistered", row);
    assertThat(p.get("email").asString()).isEqualTo("camille@exemple.fr");
    assertThat(p.get("name").asString()).isEqualTo("Camille Martin");
    assertThat(p.get("avatarColor").asString()).hasSizeLessThanOrEqualTo(20);
    assertThat(token).isNotBlank();
  }

  @Test
  @DisplayName(
      "SC-IDENT-015/016 A8 — UserProfileUpdated : HELD, changed exact ; aucun événement pour un mot de passe seul")
  void userProfileUpdatedPayload() throws Exception {
    String token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
    patchMe(token, "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}");
    assertThat(
            count("SELECT COUNT(*) FROM identity.outbox_message WHERE type = 'UserProfileUpdated'"))
        .isZero();
    patchMe(token, "{\"name\":\"Camille M.\",\"avatarColor\":\"#0EA5E9\"}");
    List<Map<String, Object>> rows =
        query("SELECT * FROM identity.outbox_message WHERE type = 'UserProfileUpdated'");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("status")).isEqualTo("HELD");
    assertThat(rows.getFirst().get("destination")).isEqualTo("broker");
    JsonNode p = JSON.readTree((String) rows.getFirst().get("payload"));
    assertThat(new ArrayList<>(p.propertyNames()))
        .containsExactlyInAnyOrder(
            "eventId", "type", "version", "occurredAt", "userId", "name", "avatarColor", "changed");
    assertEnvelope(p, "UserProfileUpdated", rows.getFirst());
    assertThat(p.get("name").asString()).isEqualTo("Camille M.");
    assertThat(p.get("avatarColor").asString()).isEqualTo("#0EA5E9");
    assertThat(p.get("changed").size()).isEqualTo(2);
    assertThat(p.get("changed").get(0).asString()).isEqualTo("name");
    assertThat(p.get("changed").get(1).asString()).isEqualTo("avatarColor");
    assertThat(p.toString()).doesNotContain("password").doesNotContain("$2y$");
  }

  private static void assertEnvelope(JsonNode p, String type, Map<String, Object> row) {
    assertThat(p.get("type").asString()).isEqualTo(type);
    assertThat(p.get("version").asInt()).isEqualTo(1);
    assertThat(UUID.fromString(p.get("eventId").asString()).version()).isEqualTo(4);
    assertThat(p.get("eventId").asString()).isEqualTo(row.get("event_id"));
    assertThat(p.get("userId").isIntegralNumber()).isTrue();
    assertThat(p.get("userId").asInt()).isEqualTo(((Number) row.get("aggregate_id")).intValue());
    OffsetDateTime at = OffsetDateTime.parse(p.get("occurredAt").asString());
    assertThat(at.getOffset().getTotalSeconds()).isZero();
  }
}
