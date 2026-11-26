package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCrypt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SC-IDENT-023 : golden fixtures/php-hashes.json produit par Symfony NativePasswordHasher (PHP
 * 8.4).
 */
class SymfonyCompatiblePasswordHasherTest {

  /**
   * Écarts délibérés au golden, fixés par l'ADR A2 (« tout autre préfixe → false ») : PHP
   * password_verify accepte aussi les hashes crypt SHA-512 ($6$), absents de la base réelle.
   */
  private static final Map<String, Boolean> A2_OVERRIDES = Map.of("unknown-sha512-crypt", false);

  private final SymfonyCompatiblePasswordHasher hasher13 = new SymfonyCompatiblePasswordHasher(13);
  private final SymfonyCompatiblePasswordHasher hasher4 = new SymfonyCompatiblePasswordHasher(4);

  static Stream<Arguments> golden() {
    try (InputStream in =
        SymfonyCompatiblePasswordHasherTest.class.getResourceAsStream(
            "/fixtures/php-hashes.json")) {
      List<Arguments> out = new ArrayList<>();
      for (JsonNode n : JsonMapper.builder().build().readTree(in)) {
        String label = n.get("label").asString();
        boolean expected = A2_OVERRIDES.getOrDefault(label, n.get("expectedValid").asBoolean());
        out.add(
            Arguments.of(label, n.get("password").asString(), n.get("hash").asString(), expected));
      }
      return out.stream();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @ParameterizedTest(name = "[{index}] {0} → {3}")
  @MethodSource("golden")
  @DisplayName(
      "SC-IDENT-023 — vérification identique à Symfony pour 100 % du golden ($2y$ 13/4, $2a$, $2b$, argon2id/i, sodium, >72 octets, NUL, 4096, corrompus)")
  void verifiesLikeSymfony(String label, String password, String hash, boolean expected) {
    assertThat(hasher13.matches(PlainPassword.of(password), new HashedPassword(hash)))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("SC-IDENT-023 — nouveau hash : $2y$13$, 60 caractères, vérifiable")
  void newHashFormat() {
    HashedPassword h = hasher13.hash(PlainPassword.of("motdepasse"));
    assertThat(h.value()).startsWith("$2y$13$").hasSize(60);
    assertThat(hasher13.matches(PlainPassword.of("motdepasse"), h)).isTrue();
    assertThat(hasher13.matches(PlainPassword.of("motdepasse2"), h)).isFalse();
  }

  @Test
  @DisplayName("SC-IDENT-023 — coût configurable (4 en test)")
  void configurableCost() {
    assertThat(hasher4.hash(PlainPassword.of("motdepasse")).value()).startsWith("$2y$04$");
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — 100 octets : hash Java vérifiable par l'algorithme PHP (base64(sha512) puis 72 octets)")
  void longPasswordPrehashedLikePhp() throws Exception {
    String pw = "c".repeat(50) + "é".repeat(25); // 100 octets
    HashedPassword h = hasher4.hash(PlainPassword.of(pw));
    String prehash =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-512").digest(pw.getBytes(StandardCharsets.UTF_8)));
    assertThat(prehash).hasSize(88);
    String $2a = "$2a$" + h.value().substring(4);
    assertThat(BCrypt.checkpw(prehash.substring(0, 72), $2a)).as("pré-hachage PHP").isTrue();
    String first72 =
        new String(pw.getBytes(StandardCharsets.UTF_8), 0, 72, StandardCharsets.ISO_8859_1);
    assertThat(BCrypt.checkpw(first72, $2a)).as("pas de troncature naïve à 72 octets").isFalse();
  }

  @Test
  @DisplayName("SC-IDENT-023 — mot de passe contenant NUL : pré-haché comme PHP")
  void nulPrehashed() throws Exception {
    String pw = "avant\0apres";
    HashedPassword h = hasher4.hash(PlainPassword.of(pw));
    String prehash =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-512").digest(pw.getBytes(StandardCharsets.UTF_8)));
    assertThat(BCrypt.checkpw(prehash.substring(0, 72), "$2a$" + h.value().substring(4))).isTrue();
    assertThat(hasher4.matches(PlainPassword.of("avant"), h)).isFalse();
  }

  @Test
  @DisplayName("SC-IDENT-023 — 72 et 73 octets se distinguent (pas de troncature silencieuse)")
  void boundary72() {
    HashedPassword h = hasher4.hash(PlainPassword.of("a".repeat(72)));
    assertThat(hasher4.matches(PlainPassword.of("a".repeat(72)), h)).isTrue();
    assertThat(hasher4.matches(PlainPassword.of("a".repeat(72) + "b"), h)).isFalse();
  }

  @Test
  @DisplayName("SC-IDENT-023 — mot de passe vide → false")
  void emptyIsFalse() {
    HashedPassword h = hasher4.hash(PlainPassword.of("motdepasse"));
    assertThat(hasher4.matches(PlainPassword.of(""), h)).isFalse();
  }

  @Test
  @DisplayName(
      "SC-IDENT-028 E15 — > 4096 octets : false en vérification, PasswordTooLongException en hachage (garde défensive)")
  void over4096() {
    HashedPassword h = hasher4.hash(PlainPassword.of("d".repeat(4096)));
    assertThat(hasher4.matches(PlainPassword.of("d".repeat(4096)), h)).isTrue();
    assertThat(hasher4.matches(PlainPassword.of("d".repeat(4097)), h)).isFalse();
    assertThatThrownBy(() -> hasher4.hash(PlainPassword.of("d".repeat(4097))))
        .isInstanceOf(PasswordTooLongException.class);
  }

  @Test
  @DisplayName("SC-IDENT-023 — hash corrompu : false, jamais d'exception (jamais de 500)")
  void corruptNeverThrows() {
    for (String bad :
        List.of(
            "",
            "$2y$",
            "$2y$13$tropcourt",
            "$argon2id$v=19$x",
            "n'importe quoi",
            "$2y$04$" + "!".repeat(53))) {
      assertThatCode(() ->
              assertThat(hasher4.matches(PlainPassword.of("motdepasse"), new HashedPassword(bad)))
                  .isFalse())
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("SC-IDENT-008 — vérification factice sans exception")
  void dummy() {
    assertThatCode(() -> hasher4.verifyAgainstDummy(PlainPassword.of("motdepasse")))
        .doesNotThrowAnyException();
    assertThatCode(() -> hasher4.verifyAgainstDummy(PlainPassword.of("")))
        .doesNotThrowAnyException();
  }
}
