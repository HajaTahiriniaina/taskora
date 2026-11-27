package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.application.port.out.VerifiedToken;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.TokenRejection;
import com.taskora.identity.support.MutableClock;
import com.taskora.identity.support.TestKeys;
import com.taskora.identity.support.TokenFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SC-IDENT-012 : précédence des refus du §8.1 (Lexik + lcobucci, tolérance 0 s). */
class NimbusTokenVerifierTest {
  private static final String U = "camille@exemple.fr";
  @TempDir static Path dir;
  private static TestKeys keys;
  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-26T09:00:00Z"));
  private NimbusTokenVerifier verifier;
  private TokenFactory tokens;

  @BeforeAll
  static void keys() {
    keys = TestKeys.generate(dir, TestKeys.Cipher.DES3);
  }

  @BeforeEach
  void setUp() {
    CLOCK.set(Instant.parse("2026-11-26T09:00:00Z"));
    tokens = new TokenFactory(keys, CLOCK);
    verifier = new NimbusTokenVerifier(keys.publicKey(), CLOCK);
  }

  static Stream<Arguments> rejected() {
    return Stream.of(
        Arguments.of(
            "expiré", (Function<TokenFactory, String>) t -> t.expired(U), TokenRejection.EXPIRED),
        Arguments.of(
            "exp == maintenant (tolérance 0)",
            (Function<TokenFactory, String>) t -> t.expiringNow(U),
            TokenRejection.EXPIRED),
        Arguments.of(
            "expiré ET signature fausse → Expired l'emporte",
            (Function<TokenFactory, String>) t -> t.expiredWithBadSignature(U),
            TokenRejection.EXPIRED),
        Arguments.of(
            "signature altérée",
            (Function<TokenFactory, String>) t -> t.badSignature(U),
            TokenRejection.INVALID),
        Arguments.of(
            "alg none", (Function<TokenFactory, String>) t -> t.algNone(U), TokenRejection.INVALID),
        Arguments.of(
            "HS256 signé avec la clé publique",
            (Function<TokenFactory, String>) t -> t.hs256WithPublicKey(U),
            TokenRejection.INVALID),
        Arguments.of(
            "autre clé",
            (Function<TokenFactory, String>) t -> t.signedByOtherKey(U),
            TokenRejection.INVALID),
        Arguments.of(
            "RS512 (seul RS256 accepté)",
            (Function<TokenFactory, String>) t -> t.rs512(U),
            TokenRejection.INVALID),
        Arguments.of(
            "exp absent",
            (Function<TokenFactory, String>) t -> t.withoutExp(U),
            TokenRejection.INVALID),
        Arguments.of(
            "exp chaîne non numérique",
            (Function<TokenFactory, String>) t -> t.expAsNonNumericString(U),
            TokenRejection.INVALID),
        Arguments.of(
            "exp chaîne NaN",
            (Function<TokenFactory, String>) t -> t.expAsNaN(U),
            TokenRejection.INVALID),
        Arguments.of(
            "exp chaîne <n>d",
            (Function<TokenFactory, String>) t -> t.expAsSuffixedNumber(U),
            TokenRejection.INVALID),
        Arguments.of(
            "exp chaîne numérique passée",
            (Function<TokenFactory, String>) t -> t.expiredAsString(U),
            TokenRejection.EXPIRED),
        Arguments.of(
            "iat futur",
            (Function<TokenFactory, String>) t -> t.futureIat(U),
            TokenRejection.INVALID),
        Arguments.of(
            "nbf futur",
            (Function<TokenFactory, String>) t -> t.futureNbf(U),
            TokenRejection.INVALID),
        Arguments.of(
            "illisible",
            (Function<TokenFactory, String>) t -> "pas-un-jeton",
            TokenRejection.INVALID),
        Arguments.of(
            "3 parties non base64",
            (Function<TokenFactory, String>) t -> "a.b.c",
            TokenRejection.INVALID),
        Arguments.of("vide", (Function<TokenFactory, String>) t -> "", TokenRejection.INVALID),
        Arguments.of(
            "username absent",
            (Function<TokenFactory, String>) TokenFactory::withoutUsername,
            TokenRejection.MISSING_USERNAME));
  }

  @ParameterizedTest(name = "{0} → {2}")
  @MethodSource("rejected")
  @DisplayName(
      "SC-IDENT-012 — chaque variante de jeton est refusée avec la raison de la précédence §8.1")
  void rejects(String label, Function<TokenFactory, String> token, TokenRejection expected) {
    String t = token.apply(tokens);
    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOfSatisfying(
            TokenRejectedException.class, e -> assertThat(e.reason()).isEqualTo(expected));
  }

  @Test
  @DisplayName("SC-IDENT-012 — expiré avec iat futur : Expired l'emporte")
  void expiredWinsOverFutureIat() {
    Map<String, Object> c = tokens.symfonyClaims(U);
    c.put("iat", CLOCK.epochSecond() + 100);
    c.put("exp", CLOCK.epochSecond() - 1);
    String t = tokens.rs256(TokenFactory.lexikHeader(), c, keys.privateKey());
    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.EXPIRED));
  }

  @Test
  @DisplayName("SC-IDENT-010 — jeton Symfony valide (sans uid) accepté")
  void acceptsSymfonyToken() {
    VerifiedToken v = verifier.verify(tokens.valid(U));
    assertThat(v.username()).isEqualTo(U);
    assertThat(v.claims()).containsKeys("iat", "exp", "roles", "username");
  }

  @Test
  @DisplayName(
      "SC-IDENT-012 — exp en chaîne numérique valide (is_numeric PHP, lcobucci) accepté (E14)")
  void acceptsNumericStringExp() {
    assertThat(verifier.verify(tokens.expAsString(U)).username()).isEqualTo(U);
  }

  @Test
  @DisplayName("SC-IDENT-010 — jeton identity (avec uid) accepté")
  void acceptsIdentityToken() {
    assertThat(verifier.verify(tokens.withUid(U, 4)).username()).isEqualTo(U);
  }

  @Test
  @DisplayName(
      "SC-IDENT-012 — tolérance 0 s : exp = maintenant + 1 accepté, puis refusé une seconde plus tard")
  void zeroLeeway() {
    Map<String, Object> c = tokens.symfonyClaims(U);
    c.put("exp", CLOCK.epochSecond() + 1);
    String t = tokens.rs256(TokenFactory.lexikHeader(), c, keys.privateKey());
    assertThat(verifier.verify(t).username()).isEqualTo(U);
    CLOCK.advance(java.time.Duration.ofSeconds(1));
    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.EXPIRED));
  }

  @Test
  @DisplayName("SC-IDENT-012 — iat = maintenant + 1 → invalide (tolérance 0)")
  void iatOneSecondInFuture() {
    Map<String, Object> c = tokens.symfonyClaims(U);
    c.put("iat", CLOCK.epochSecond() + 1);
    String t = tokens.rs256(TokenFactory.lexikHeader(), c, keys.privateKey());
    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.INVALID));
  }
}
