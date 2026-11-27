package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * B1 (ordre de travail phase-1-identity-perf-b1 §5.1, point 2) : le moteur bcrypt natif (libxcrypt
 * via FFM) produit exactement la sortie du moteur Java et de PHP, sans affaiblir SC-IDENT-023
 * (compatibilité Symfony) ni SC-IDENT-008 (hash factice sur le même moteur).
 *
 * <p>S'exécute sous Linux (conteneur eclipse-temurin:25-jdk, CI), où {@code libcrypt.so.1} existe.
 */
class NativeBcryptEngineCompatibilityTest {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String HUNDRED_BYTES = "c".repeat(50) + "é".repeat(25);

  private final JavaBcryptEngine javaEngine = new JavaBcryptEngine();

  private static BcryptEngine nativeEngine() {
    return new LibxcryptBcryptEngine();
  }

  private static SymfonyCompatiblePasswordHasher nativeHasher(int cost) {
    return new SymfonyCompatiblePasswordHasher(cost, nativeEngine());
  }

  private static byte[] input(String password) {
    return SymfonyCompatiblePasswordHasher.bcryptInput(password);
  }

  private static String prehash(String password) throws Exception {
    return Base64.getEncoder()
        .encodeToString(
            MessageDigest.getInstance("SHA-512").digest(password.getBytes(StandardCharsets.UTF_8)));
  }

  static Stream<Arguments> sameOutputCases() {
    List<String> passwords =
        List.of(
            "motdepasse",
            "a".repeat(72),
            "a".repeat(72) + "b",
            "avant\0apres",
            HUNDRED_BYTES,
            "é".repeat(36),
            "é".repeat(37),
            "mötdèpàsse-日本語-😀");
    List<Arguments> out = new ArrayList<>();
    for (String prefix : List.of("$2y", "$2a", "$2b")) {
      for (String pw : passwords) {
        out.add(Arguments.of(prefix, 4, pw));
      }
    }
    out.add(Arguments.of("$2y", 13, "motdepasse"));
    out.add(Arguments.of("$2y", 13, "a".repeat(72) + "b"));
    return out.stream();
  }

  @ParameterizedTest(name = "[{index}] {0} coût {1}, {2}")
  @MethodSource("sameOutputCases")
  @DisplayName(
      "B1 / SC-IDENT-023 — moteur natif : sortie identique au moteur Java pour un même sel ($2y$, $2a$, $2b$, coûts 4 et 13, 72/73 octets, NUL, 100 octets, UTF-8)")
  void sameOutputAsJavaEngine(String prefix, int cost, String password) {
    String salt = BCrypt.gensalt(prefix, cost, RANDOM);
    byte[] in = input(password);

    String expected = javaEngine.crypt(in, salt);
    String actual = nativeEngine().crypt(in, salt);

    assertThat(expected).as("moteur Java").isNotNull();
    assertThat(actual)
        .as("moteur natif")
        .isEqualTo(expected)
        .startsWith(salt.substring(0, 7))
        .hasSize(60);
    assertThat(nativeEngine().crypt(in, actual))
        .as("vérification : le hash complet sert de réglage")
        .isEqualTo(actual);
  }

  @ParameterizedTest(name = "[{index}] {0} → {3}")
  @MethodSource(
      "com.taskora.identity.adapter.out.security.SymfonyCompatiblePasswordHasherTest#golden")
  @DisplayName(
      "B1 / SC-IDENT-023 — moteur natif : golden PHP → Java rejoué (100 % identique à Symfony)")
  void goldenWithNativeEngine(String label, String password, String hash, boolean expected) {
    assertThat(nativeHasher(13).matches(PlainPassword.of(password), new HashedPassword(hash)))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "B1 / SC-IDENT-023 — moteur natif : nouveau hash $2y$13$, 60 caractères, lisible par le moteur Java (algorithme PHP)")
  void nativeHashReadableByJava() {
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(13);
    HashedPassword h = hasher.hash(PlainPassword.of("motdepasse"));

    assertThat(h.value()).startsWith("$2y$13$").hasSize(60);
    assertThat(BCrypt.checkpw("motdepasse", "$2a$" + h.value().substring(4))).isTrue();
    assertThat(hasher.matches(PlainPassword.of("motdepasse"), h)).isTrue();
    assertThat(hasher.matches(PlainPassword.of("motdepasse2"), h)).isFalse();
  }

  @Test
  @DisplayName(
      "B1 / SC-IDENT-023 — moteur natif, 100 octets : base64(sha512) puis 72 octets, comme PHP (Java → PHP)")
  void longPasswordPrehashedLikePhp() throws Exception {
    HashedPassword h = nativeHasher(4).hash(PlainPassword.of(HUNDRED_BYTES));
    String $2a = "$2a$" + h.value().substring(4);

    assertThat(BCrypt.checkpw(prehash(HUNDRED_BYTES).substring(0, 72), $2a))
        .as("pré-hachage PHP")
        .isTrue();
    String first72 =
        new String(
            HUNDRED_BYTES.getBytes(StandardCharsets.UTF_8), 0, 72, StandardCharsets.ISO_8859_1);
    assertThat(BCrypt.checkpw(first72, $2a)).as("pas de troncature naïve à 72 octets").isFalse();
  }

  @Test
  @DisplayName("B1 / SC-IDENT-023 — moteur natif, NUL : pré-haché comme PHP, jamais tronqué au NUL")
  void nulPrehashed() throws Exception {
    String pw = "avant\0apres";
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(4);
    HashedPassword h = hasher.hash(PlainPassword.of(pw));

    assertThat(BCrypt.checkpw(prehash(pw).substring(0, 72), "$2a$" + h.value().substring(4)))
        .isTrue();
    assertThat(hasher.matches(PlainPassword.of("avant"), h)).isFalse();
    assertThat(hasher.matches(PlainPassword.of(pw), h)).isTrue();
  }

  @Test
  @DisplayName("B1 / SC-IDENT-023 — moteur natif : 72 et 73 octets se distinguent")
  void boundary72() {
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(4);
    HashedPassword h = hasher.hash(PlainPassword.of("a".repeat(72)));

    assertThat(hasher.matches(PlainPassword.of("a".repeat(72)), h)).isTrue();
    assertThat(hasher.matches(PlainPassword.of("a".repeat(72) + "b"), h)).isFalse();
  }

  @Test
  @DisplayName(
      "B1 / SC-IDENT-023 — moteur natif : hash corrompu, tronqué, préfixe inconnu ou sel invalide → false, jamais d'exception")
  void corruptHashNeverThrows() {
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(4);
    String valid = hasher.hash(PlainPassword.of("motdepasse")).value();
    for (String bad :
        List.of(
            "",
            "$2y$",
            "$2y$13$tropcourt",
            valid.substring(0, 59),
            "$2x$" + valid.substring(4),
            "$2z$" + valid.substring(4),
            "$2y$03$" + valid.substring(7),
            "$2y$32$" + valid.substring(7),
            "$2y$04$" + "!".repeat(53),
            "$2y$04$" + "!".repeat(22) + valid.substring(29),
            "$argon2id$v=19$x",
            "n'importe quoi")) {
      assertThatCode(() ->
              assertThat(hasher.matches(PlainPassword.of("motdepasse"), new HashedPassword(bad)))
                  .as("hash %s", bad)
                  .isFalse())
          .doesNotThrowAnyException();
    }
  }

  @ParameterizedTest(name = "[{index}] réglage {0}")
  @ValueSource(
      strings = {
        "",
        "$2y$",
        "$2y$04$tropcourt",
        "$2y$04$!!!!!!!!!!!!!!!!!!!!!!",
        "$2y$03$abcdefghijklmnopqrstuu",
        "$2y$32$abcdefghijklmnopqrstuu",
        "$2y$04$!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
      })
  @DisplayName("B1 — moteur natif : réglage bcrypt invalide → null, jamais d'exception")
  void invalidSettingReturnsNull(String setting) {
    BcryptEngine engine = nativeEngine();
    assertThatCode(() -> assertThat(engine.crypt(input("motdepasse"), setting)).isNull())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "B1 — moteur natif : 16 vérifications concurrentes correctes (aucun état partagé entre appels)")
  void concurrentVerifications() throws Exception {
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(4);
    HashedPassword stored =
        new HashedPassword(BCrypt.hashpw(input("motdepasse"), BCrypt.gensalt("$2y", 4, RANDOM)));
    int threads = 16;
    int rounds = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int id = t;
        futures.add(
            pool.submit(() -> {
              start.await();
              List<String> errors = new ArrayList<>();
              for (int r = 0; r < rounds; r++) {
                boolean good = (id + r) % 2 == 0;
                String pw = good ? "motdepasse" : "mauvais-" + id + "-" + r;
                if (hasher.matches(PlainPassword.of(pw), stored) != good) {
                  errors.add("fil " + id + ", tour " + r + " : attendu " + good);
                }
              }
              return errors;
            }));
      }
      start.countDown();
      List<String> errors = new ArrayList<>();
      for (Future<List<String>> f : futures) {
        errors.addAll(f.get(60, TimeUnit.SECONDS));
      }
      assertThat(errors).isEmpty();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "B1 / SC-IDENT-008 — moteur natif : vérification factice sans exception (même moteur que le vrai hash)")
  void dummyOnNativeEngine() {
    SymfonyCompatiblePasswordHasher hasher = nativeHasher(4);
    assertThatCode(() -> hasher.verifyAgainstDummy(PlainPassword.of("motdepasse")))
        .doesNotThrowAnyException();
    assertThatCode(() -> hasher.verifyAgainstDummy(PlainPassword.of("")))
        .doesNotThrowAnyException();
    assertThat(hasher.engine()).isInstanceOf(LibxcryptBcryptEngine.class);
  }

  @Test
  @DisplayName(
      "B1 — bibliothèque native introuvable → échec explicite (bibliothèque et propriété nommées, aucune donnée sensible)")
  void missingLibraryFailsExplicitly() {
    String library = "libcrypt-absente-b1.so.0";
    assertThatThrownBy(() -> new LibxcryptBcryptEngine(library))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(library)
        .hasMessageContaining("identity.password.bcrypt-engine");
  }

  @Test
  @DisplayName(
      "B1 — moteur java sélectionnable explicitement : hachage $2y$, vérification et hash factice")
  void javaEngineSelectable() {
    SymfonyCompatiblePasswordHasher hasher =
        new SymfonyCompatiblePasswordHasher(4, new JavaBcryptEngine());
    HashedPassword h = hasher.hash(PlainPassword.of("motdepasse"));

    assertThat(hasher.engine()).isInstanceOf(JavaBcryptEngine.class);
    assertThat(h.value()).startsWith("$2y$04$").hasSize(60);
    assertThat(hasher.matches(PlainPassword.of("motdepasse"), h)).isTrue();
    assertThat(hasher.matches(PlainPassword.of("mauvais"), h)).isFalse();
    assertThatCode(() -> hasher.verifyAgainstDummy(PlainPassword.of("motdepasse")))
        .doesNotThrowAnyException();
  }
}
