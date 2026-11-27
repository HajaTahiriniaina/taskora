package com.taskora.board.security;

import static com.taskora.board.support.Fixture.ALICE;
import static com.taskora.board.support.Fixture.ALICE_EMAIL;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.Json;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SC-BOARD-020 (B-ADR-2) — jetons d'identity (uid) et de Symfony (sans uid) acceptés ; même table
 * de refus que SC-IDENT-010 avec les corps Lexik exacts et {@code WWW-Authenticate: Bearer}.
 */
class JwtSecurityIT extends ApiTestBase {
  private static final String PATH = "/api/boards";

  @Test
  @DisplayName(
      "SC-BOARD-020 — jeton identity (uid) et jeton Symfony (sans uid) → 200 et la même liste")
  void bothIssuersAccepted() {
    Response identity = get(TOKENS.identity(ALICE_EMAIL, ALICE), PATH);
    Response symfony = get(TOKENS.symfony(ALICE_EMAIL), PATH);
    assertStatus(identity, 200);
    assertStatus(symfony, 200);
    assertThat(json(identity)).isEqualTo(json(symfony));
    assertThat(json(identity)).hasSize(2);
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — username d'un jeton Symfony comparé avec la collation (casse, espaces finaux)")
  void usernameCollation() {
    assertThat(json(get(TOKENS.symfony("ALICE@Exemple.TEST  "), PATH)))
        .isEqualTo(json(get(TOKENS.symfony(ALICE_EMAIL), PATH)));
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — exp en chaîne numérique valide (is_numeric PHP, lcobucci) → 200 (E14)")
  void expNumericString() {
    assertStatus(get(TOKENS.expAsString(ALICE_EMAIL), PATH), 200);
  }

  @Test
  @DisplayName("SC-BOARD-020 — uid seul (sans username) → 200")
  void uidOnly() {
    assertStatus(get(TOKENS.uidOnly(ALICE), PATH), 200);
  }

  static Stream<Arguments> rejectedTokens() {
    return Stream.of(
        Arguments.of(
            "expiré", (Supplier<String>) () -> TOKENS.expired(ALICE_EMAIL), "Expired JWT Token"),
        Arguments.of(
            "exp = maintenant (tolérance 0)",
            (Supplier<String>) () -> TOKENS.expiringNow(ALICE_EMAIL),
            "Expired JWT Token"),
        Arguments.of(
            "expiré + signature fausse (expiration prioritaire)",
            (Supplier<String>) () -> TOKENS.expiredWithBadSignature(ALICE_EMAIL),
            "Expired JWT Token"),
        Arguments.of(
            "signature fausse",
            (Supplier<String>) () -> TOKENS.badSignature(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "alg none", (Supplier<String>) () -> TOKENS.algNone(ALICE_EMAIL), "Invalid JWT Token"),
        Arguments.of(
            "HS256 signé avec la clé publique",
            (Supplier<String>) () -> TOKENS.hs256WithPublicKey(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "autre clé",
            (Supplier<String>) () -> TOKENS.signedByOtherKey(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "RS512", (Supplier<String>) () -> TOKENS.rs512(ALICE_EMAIL), "Invalid JWT Token"),
        Arguments.of(
            "iat futur",
            (Supplier<String>) () -> TOKENS.futureIat(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "exp absent",
            (Supplier<String>) () -> TOKENS.withoutExp(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "exp chaîne non numérique",
            (Supplier<String>) () -> TOKENS.expAsNonNumericString(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "exp chaîne NaN",
            (Supplier<String>) () -> TOKENS.expAsNaN(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "exp chaîne <n>d",
            (Supplier<String>) () -> TOKENS.expAsSuffixedNumber(ALICE_EMAIL),
            "Invalid JWT Token"),
        Arguments.of(
            "exp chaîne numérique passée",
            (Supplier<String>) () -> TOKENS.expiredAsString(ALICE_EMAIL),
            "Expired JWT Token"),
        Arguments.of("charabia", (Supplier<String>) () -> "pas.un.jeton", "Invalid JWT Token"),
        Arguments.of(
            "uid et username incohérents",
            (Supplier<String>) () -> TOKENS.identity(Fixture.BRUNO_EMAIL, ALICE),
            "Invalid JWT Token"),
        Arguments.of(
            "compte disparu (uid)",
            (Supplier<String>) () -> TOKENS.identity("disparu@exemple.test", 4242),
            "Invalid credentials."),
        Arguments.of(
            "compte disparu (username)",
            (Supplier<String>) () -> TOKENS.symfony("disparu@exemple.test"),
            "Invalid credentials."),
        Arguments.of(
            "compte disparu (uid seul)",
            (Supplier<String>) () -> TOKENS.uidOnly(4242),
            "Invalid credentials."),
        Arguments.of(
            "ni uid ni username",
            (Supplier<String>) TOKENS::withoutUsernameNorUid,
            "Unable to find key \"username\" in the token payload."));
  }

  @ParameterizedTest(name = "{0} → {2}")
  @MethodSource("rejectedTokens")
  @DisplayName("SC-BOARD-020 — jeton refusé : 401 Lexik exact + WWW-Authenticate: Bearer")
  void rejected(String label, Supplier<String> token, String message) {
    assertAuthError(get(token.get(), PATH), message);
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — compte supprimé entre deux requêtes → 401 Invalid credentials. dès la suivante")
  void accountRemovedBetweenRequests() {
    String token = TOKENS.symfony(Fixture.NADIA_EMAIL);
    assertStatus(get(token, PATH), 200);
    BoardDatabase.rootExecute("DELETE FROM taskora.`user` WHERE id = ?", Fixture.NADIA);
    assertAuthError(get(token, PATH), "Invalid credentials.");
  }

  @ParameterizedTest(name = "Authorization: \"{0}\"")
  @ValueSource(strings = {"", "Bearer", "Bearer ", "Basic YWxpY2U6eA=="})
  @DisplayName("SC-BOARD-020 — en-tête absent ou mal formé → 401 JWT Token not found")
  void notFound(String header) {
    Response r =
        given().baseUri("http://localhost").port(port).header("Authorization", header).get(PATH);
    assertAuthError(r, "JWT Token not found");
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — préfixe « bearer » insensible à la casse (strcasecmp Lexik) : jeton extrait puis vérifié")
  void lowercasePrefix() {
    Response garbage =
        given()
            .baseUri("http://localhost")
            .port(port)
            .header("Authorization", "bearer x")
            .get(PATH);
    assertAuthError(garbage, "Invalid JWT Token");
    Response valid =
        given()
            .baseUri("http://localhost")
            .port(port)
            .header("Authorization", "bearer " + TOKENS.symfony(ALICE_EMAIL))
            .get(PATH);
    assertStatus(valid, 200);
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — sans en-tête Authorization → 401 JWT Token not found sur toutes les routes T1")
  void anonymousEverywhere() {
    for (String[] route :
        List.of(
            new String[] {"GET", "/api/boards"},
            new String[] {"POST", "/api/boards"},
            new String[] {"GET", "/api/boards/101"},
            new String[] {"PATCH", "/api/boards/101"},
            new String[] {"DELETE", "/api/boards/101"},
            new String[] {"POST", "/api/boards/101/favorite"},
            new String[] {"POST", "/api/boards/101/members"},
            new String[] {"PATCH", "/api/boards/101/members/3"},
            new String[] {"DELETE", "/api/boards/101/members/3"},
            new String[] {"DELETE", "/api/boards/101/invitations/301"},
            new String[] {"GET", "/api/trash/boards"},
            new String[] {"POST", "/api/trash/boards/103/restore"},
            new String[] {"GET", "/api/boards/abc"})) {
      Response r = send(route[0], null, route[1], "{}");
      assertAuthError(r, "JWT Token not found");
    }
  }

  // ------------------------------------------------------------------ B1 (§12.2, TB5)

  /** 401 Lexik exact sur une réponse brute. */
  private static void assertRawAuthError(
      SoftAssertions soft, String what, RawExchange r, String message) {
    soft.assertThat(r.status()).as("%s : statut ; corps = %s", what, r.body()).isEqualTo(401);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("code", 401);
    expected.put("message", message);
    soft.assertThat(json(r.body()))
        .as("%s : corps", what)
        .isEqualTo(JSON.readTree(Json.write(expected)));
    soft.assertThat(r.header("WWW-Authenticate"))
        .as("%s : WWW-Authenticate", what)
        .isEqualTo("Bearer");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/%61pi/boards/101", "/%61%70%69/boards/101"})
  @DisplayName("SC-BOARD-020 (B1) — préfixe /api/ encodé : même traitement JWT que /api/boards/101")
  void encodedApiPrefix(String rawPath) {
    RawExchange reference = rawInternal("GET", "/api/boards/101", null, alice(), null);
    assertThat(reference.status()).as("référence /api/boards/101").isEqualTo(200);
    SoftAssertions soft = new SoftAssertions();
    RawExchange valid = rawInternal("GET", rawPath, null, alice(), null);
    soft.assertThat(valid.status())
        .as("(a) jeton valide : statut ; corps = %s", valid.body())
        .isEqualTo(reference.status());
    soft.assertThat(json(valid.body()))
        .as("(a) jeton valide : corps")
        .isEqualTo(json(reference.body()));
    assertRawAuthError(
        soft, "(b) Bearer x", rawInternal("GET", rawPath, null, "x", null), "Invalid JWT Token");
    assertRawAuthError(
        soft,
        "(c) sans en-tête",
        rawInternal("GET", rawPath, null, null, null),
        "JWT Token not found");
    assertRawAuthError(
        soft,
        "(d) jeton expiré",
        rawInternal("GET", rawPath, null, TOKENS.expired(ALICE_EMAIL), null),
        "Expired JWT Token");
    soft.assertAll();
  }
}
