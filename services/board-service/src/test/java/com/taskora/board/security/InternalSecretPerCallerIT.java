package com.taskora.board.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.SymfonyStub;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A18, U12 (B-ADR-5 §6) — un secret par appelant : {@code /internal/invitations/claim} n'accepte
 * que {@code INTERNAL_SECRET_IDENTITY_TO_BOARD} ; board-service envoie {@code
 * INTERNAL_SECRET_BOARD_TO_SYMFONY} à Symfony ; une variable {@code INTERNAL_API_SECRET} présente
 * dans l'environnement n'est jamais lue.
 */
@TestPropertySource(
    properties = "INTERNAL_API_SECRET=" + InternalSecretPerCallerIT.IDENTITY_TO_SYMFONY)
class InternalSecretPerCallerIT extends ApiTestBase {
  static final String IDENTITY_TO_SYMFONY = "test-identity-to-symfony-0123456789abcdef";

  private static String body() {
    return "{\"eventId\":\""
        + UUID.randomUUID()
        + "\",\"userId\":9,\"email\":\"invite@exemple.test\"}";
  }

  @Test
  @DisplayName(
      "U12 — claim : refuse INTERNAL_SECRET_BOARD_TO_SYMFONY, INTERNAL_API_SECRET et un secret aléatoire (401)")
  void otherCallersSecretsRefused() {
    for (String secret :
        new String[] {
          BOARD_TO_SYMFONY_SECRET,
          IDENTITY_TO_SYMFONY,
          "aleatoire-" + UUID.randomUUID(),
          IDENTITY_TO_BOARD_SECRET.substring(1),
          ""
        }) {
      assertError(claim(secret, body()), 401, "Accès interne refusé.");
    }
    assertThat(count("SELECT COUNT(*) FROM taskora.invitation WHERE email = 'invite@exemple.test'"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("U12 — claim : accepte INTERNAL_SECRET_IDENTITY_TO_BOARD seulement")
  void identityToBoardAccepted() {
    assertStatus(claim(IDENTITY_TO_BOARD_SECRET, body()), 200);
  }

  @Test
  @DisplayName(
      "U12 — SymfonyNotificationClient envoie INTERNAL_SECRET_BOARD_TO_SYMFONY (jamais un autre secret)")
  void boardToSymfonyHeader() {
    assertStatus(
        post(alice(), "/api/boards/101/members", "{\"email\":\"dora@exemple.test\"}"), 201);
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() == 2);
    for (LoggedRequest call : SymfonyStub.notifications()) {
      assertThat(call.getHeader("X-Internal-Secret")).isEqualTo(BOARD_TO_SYMFONY_SECRET);
      assertThat(call.getHeader("Authorization")).isNull();
      assertThat(call.getHeader("Content-Type")).contains("application/json");
    }
  }

  @Test
  @DisplayName("U12 — /internal/** n'accepte jamais de JWT, même avec le bon secret absent")
  void noJwtOnInternal() {
    assertError(
        internal()
            .header(
                "Authorization", "Bearer " + TOKENS.identity(Fixture.ALICE_EMAIL, Fixture.ALICE))
            .header("X-Internal-Secret", IDENTITY_TO_SYMFONY)
            .contentType("application/json")
            .body(body())
            .post("/internal/invitations/claim"),
        401,
        "Accès interne refusé.");
  }
}
