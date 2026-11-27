package com.taskora.board.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.SymfonyStub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

  // ------------------------------------------------------------------ B1 (§12.2, TB1–TB4)

  private static final String DENIED = "{\"error\":\"Accès interne refusé.\"}";
  private static final String INVITES =
      "SELECT COUNT(*) FROM taskora.invitation WHERE email = 'invite@exemple.test'";

  /** État observable avant la requête : empreinte des données, outbox, invitations d'Inès. */
  private record Snapshot(
      Map<String, Object> fingerprint, List<Map<String, Object>> outboxRows, long invitations) {
    static Snapshot take() {
      return new Snapshot(BoardDatabase.dataFingerprint(), ApiTestBase.outbox(), count(INVITES));
    }
  }

  /** Aucune écriture : empreinte, outbox et 2 invitations en attente inchangées. */
  private static void assertNoWrite(SoftAssertions soft, Snapshot before) {
    Snapshot after = Snapshot.take();
    soft.assertThat(after.fingerprint())
        .as("empreinte des données")
        .isEqualTo(before.fingerprint());
    soft.assertThat(after.outboxRows()).as("outbox").isEqualTo(before.outboxRows());
    soft.assertThat(after.invitations()).as("invitations restantes").isEqualTo(2);
  }

  /** 401 exact {"error":"Accès interne refusé."} en JSON. */
  private static void assertDenied(SoftAssertions soft, RawExchange r) {
    soft.assertThat(r.status()).as("statut ; corps = %s", r.body()).isEqualTo(401);
    soft.assertThat(r.header("Content-Type")).as("type").startsWith("application/json");
    soft.assertThat(json(r.body())).as("corps").isEqualTo(json(DENIED));
  }

  /** Chemins décodés en {@code /internal/invitations/claim} par Spring MVC et Spring Security. */
  private static final List<String> ENCODED_CLAIM_PATHS =
      List.of(
          "/%69nternal/invitations/claim",
          "/i%6eternal/invitations/claim",
          "/%69%6E%74%65%72%6E%61%6C/invitations/claim",
          "/%69nternal/%69nvitations/%63laim",
          "/internal/%69nvitations/claim",
          "/internal/invitations/%63laim");

  static Stream<Arguments> encodedPathsAndSecrets() {
    List<Arguments> out = new ArrayList<>();
    for (String path : ENCODED_CLAIM_PATHS) {
      out.add(argumentSet(path + " — secret absent", path, null));
      out.add(argumentSet(path + " — secret vide", path, ""));
      out.add(
          argumentSet(path + " — INTERNAL_SECRET_BOARD_TO_SYMFONY", path, BOARD_TO_SYMFONY_SECRET));
      out.add(argumentSet(path + " — INTERNAL_API_SECRET", path, IDENTITY_TO_SYMFONY));
      out.add(argumentSet(path + " — aléatoire", path, "aleatoire-" + UUID.randomUUID()));
      out.add(
          argumentSet(
              path + " — secret identity tronqué", path, IDENTITY_TO_BOARD_SECRET.substring(1)));
    }
    return out.stream();
  }

  @ParameterizedTest
  @MethodSource("encodedPathsAndSecrets")
  @DisplayName(
      "U12, A18 (B1) — chemin /internal/** encodé sans le bon secret → 401 Accès interne refusé., 0 écriture")
  void encodedInternalPathRequiresSecret(String rawPath, @Nullable String secret) {
    Snapshot before = Snapshot.take();
    RawExchange r = rawInternal("POST", rawPath, secret, null, body());
    SoftAssertions soft = new SoftAssertions();
    assertDenied(soft, r);
    assertNoWrite(soft, before);
    soft.assertAll();
  }

  @Test
  @DisplayName(
      "U12 (B1) — /%69nternal/** avec un JWT valide et sans secret → 401 Accès interne refusé., 0 écriture")
  void encodedInternalPathIgnoresJwt() {
    Snapshot before = Snapshot.take();
    RawExchange r = rawInternal("POST", "/%69nternal/invitations/claim", null, alice(), body());
    SoftAssertions soft = new SoftAssertions();
    assertDenied(soft, r);
    assertNoWrite(soft, before);
    soft.assertAll();
  }

  private static final List<String> NON_NORMALIZED_PATHS =
      List.of(
          "//internal/invitations/claim",
          "/internal//invitations/claim",
          "/./internal/invitations/claim",
          "/internal/./invitations/claim",
          "/internal/../internal/invitations/claim",
          "/x/../internal/invitations/claim",
          "/internal;x=1/invitations/claim",
          "/;x=1/internal/invitations/claim",
          "/internal/invitations/claim;jsessionid=1",
          "/%2Finternal/invitations/claim",
          "/internal%2Finvitations/claim",
          "/internal%2finvitations/claim",
          "/%2e/internal/invitations/claim",
          "/%2e%2e/internal/invitations/claim",
          "/internal%5Cinvitations/claim",
          "/%25%36%39nternal/invitations/claim",
          "/internal/invitations/claim%00");

  static Stream<Arguments> nonNormalizedPathsAndSecrets() {
    List<Arguments> out = new ArrayList<>();
    for (String path : NON_NORMALIZED_PATHS) {
      out.add(argumentSet(path + " — secret absent", path, null));
      out.add(
          argumentSet(path + " — INTERNAL_SECRET_BOARD_TO_SYMFONY", path, BOARD_TO_SYMFONY_SECRET));
    }
    return out.stream();
  }

  /** Jamais 2xx ni 5xx, corps sans {@code claimed}, 0 écriture. */
  private static void assertRefusedWithoutWrite(
      RawExchange r, Snapshot before, Integer... allowed) {
    SoftAssertions soft = new SoftAssertions();
    soft.assertThat(r.status()).as("statut ; corps = %s", r.body()).isIn((Object[]) allowed);
    soft.assertThat(r.body()).as("corps").doesNotContain("claimed");
    assertNoWrite(soft, before);
    soft.assertAll();
  }

  @ParameterizedTest
  @MethodSource("nonNormalizedPathsAndSecrets")
  @DisplayName(
      "U12 (B1, garde-fou) — chemin non normalisé vers /internal/** → 400 ou 401, jamais 2xx ni 5xx, 0 écriture")
  void nonNormalizedInternalPathNeverAccepted(String rawPath, @Nullable String secret) {
    Snapshot before = Snapshot.take();
    assertRefusedWithoutWrite(rawInternal("POST", rawPath, secret, null, body()), before, 400, 401);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/INTERNAL/invitations/claim",
        "/Internal/invitations/claim",
        "/%49nternal/invitations/claim",
        "/internal/invitations/claim/",
        "/internal/invitations/claim.json"
      })
  @DisplayName(
      "U12 (B1, garde-fou) — autres graphies de /internal/** sans secret → 401 ou 404, jamais 2xx, 0 écriture")
  void otherSpellingsNeverAccepted(String rawPath) {
    Snapshot before = Snapshot.take();
    assertRefusedWithoutWrite(rawInternal("POST", rawPath, null, null, body()), before, 401, 404);
  }
}
