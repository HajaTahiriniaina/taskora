package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * B1 / PERF-IDENT-1 (identity.md §6.1) : bcrypt coût 13 d'identity-service au plus 1,15 fois plus
 * lent que PHP {@code password_verify} / {@code password_hash} (Symfony), mesuré sur la même
 * machine. Comparaison relative : indépendante du matériel du CI.
 *
 * <p>Protocole :
 *
 * <ul>
 *   <li>PHP : image du backend figée par digest (celle de {@code backend/Dockerfile}), mesure
 *       {@code hrtime} dans le conteneur (le démarrage du processus {@code php} est exclu) ;
 *   <li>Java : {@link SymfonyCompatiblePasswordHasher} construit comme en production (moteur par
 *       défaut), mesuré par {@code System.nanoTime} dans la JVM de test ;
 *   <li>blocs alternés PHP, Java, PHP, Java, jamais simultanés ; chauffe exclue à chaque bloc (PHP
 *       : 3 appels par processus ; Java : 5 au premier bloc, 2 ensuite) ; {@value #PER_BLOCK}
 *       mesures par bloc, soit {@value #MEASURED} par implémentation et par opération ; critère sur
 *       les médianes regroupées (la médiane résiste aux pauses ponctuelles des runners partagés).
 * </ul>
 *
 * <p>Durée bornée : 4 × ({@value #PER_BLOCK} + chauffe) opérations coût 13 par opération mesurée,
 * soit environ 90 appels bcrypt au total : ≈ 50 s sur Apple M1, moins de 2 min sur un runner CI
 * ubuntu (plus le premier téléchargement de l'image PHP).
 */
class BcryptLatencyIT {
  /** Image PHP du backend (backend/Dockerfile, index multi-architecture amd64 / arm64). */
  static final String PHP_IMAGE =
      "dunglas/frankenphp:1-php8.4-alpine@sha256:f81693fa548684eb12964eeab78d86325b75c5cd91289c09ffc4dd4404a641e5";

  /** Seuil PERF-IDENT-1 (L1, L2) : ne pas relever. */
  static final double MAX_RATIO = 1.15;

  static final int COST = 13;
  static final int BLOCKS = 2;
  static final int PER_BLOCK = 8;
  static final int MEASURED = BLOCKS * PER_BLOCK;
  static final int PHP_WARMUP = 3;
  static final int JAVA_FIRST_WARMUP = 5;
  static final int JAVA_WARMUP = 2;

  /** Mot de passe de banc (valeur de test, < 72 octets : pas de pré-hachage). */
  static final String PASSWORD = "banc-latence-b1-motdepasse";

  private static final String SCRIPT_PATH = "/opt/bcrypt-bench.php";

  /**
   * Usage : {@code php bcrypt-bench.php make|verify|hash <chauffe> <n> <mot de passe> [hash]}.
   * Imprime un hash ($2y$ coût 13, op make) ou une durée en nanosecondes par ligne ; code 2 si une
   * vérification échoue.
   */
  private static final String SCRIPT =
      """
      <?php
      [, $op, $warmup, $n, $password] = $argv;
      $hash = $argv[5] ?? '';
      if ($op === 'make') {
          echo password_hash($password, PASSWORD_BCRYPT, ['cost' => %d]), "\\n";
          exit(0);
      }
      $run = $op === 'verify'
          ? static function () use ($password, $hash): void {
              if (!password_verify($password, $hash)) {
                  fwrite(STDERR, "password_verify a renvoyé false\\n");
                  exit(2);
              }
          }
          : static function () use ($password): void {
              password_hash($password, PASSWORD_BCRYPT, ['cost' => %d]);
          };
      for ($i = 0; $i < (int) $warmup; $i++) {
          $run();
      }
      for ($i = 0; $i < (int) $n; $i++) {
          $t = hrtime(true);
          $run();
          echo hrtime(true) - $t, "\\n";
      }
      """
          .formatted(COST, COST);

  private static GenericContainer<?> php;

  @BeforeAll
  @SuppressWarnings("resource")
  static void startPhp() {
    php =
        new GenericContainer<>(DockerImageName.parse(PHP_IMAGE))
            .withCommand("tail", "-f", "/dev/null")
            .withCopyToContainer(Transferable.of(SCRIPT), SCRIPT_PATH);
    php.start();
  }

  @AfterAll
  static void stopPhp() {
    if (php != null) {
      php.stop();
    }
  }

  @Test
  @DisplayName(
      "B1 — vérification bcrypt $2y$ coût 13 : médiane identity ≤ 1,15 × médiane PHP password_verify (PERF-IDENT-1, login)")
  void verifyWithinMarginOfPhp() throws Exception {
    String phpHash = phpOut("make", 0, 0, "").getFirst();
    assertThat(phpHash).startsWith("$2y$13$").hasSize(60);
    SymfonyCompatiblePasswordHasher hasher = new SymfonyCompatiblePasswordHasher(COST);
    PlainPassword password = PlainPassword.of(PASSWORD);
    HashedPassword stored = new HashedPassword(phpHash);
    assertThat(hasher.matches(password, stored)).as("hash PHP vérifié par identity").isTrue();

    compare(
        "verify",
        phpHash,
        () -> hasher.matches(password, stored),
        "vérification (login) : identity matches vs PHP password_verify");
  }

  @Test
  @DisplayName(
      "B1 — hachage bcrypt $2y$ coût 13 : médiane identity ≤ 1,15 × médiane PHP password_hash (PERF-IDENT-1, inscription)")
  void hashWithinMarginOfPhp() throws Exception {
    SymfonyCompatiblePasswordHasher hasher = new SymfonyCompatiblePasswordHasher(COST);
    PlainPassword password = PlainPassword.of(PASSWORD);
    HashedPassword produced = hasher.hash(password);
    assertThat(produced.value()).startsWith("$2y$13$").hasSize(60);
    phpOut("verify", 0, 1, produced.value()); // hash identity lisible par PHP (code 0)

    compare(
        "hash",
        "",
        () -> hasher.hash(password).value().startsWith("$2y$13$"),
        "hachage (inscription) : identity hash vs PHP password_hash");
  }

  /** Blocs alternés PHP / Java, médianes regroupées, assertion du rapport. */
  private static void compare(String op, String hash, BooleanSupplier javaCall, String what)
      throws Exception {
    List<Double> phpAll = new ArrayList<>();
    List<Double> javaAll = new ArrayList<>();
    List<String> blocks = new ArrayList<>();
    for (int b = 0; b < BLOCKS; b++) {
      List<Double> p = phpBlock(op, hash);
      List<Double> j = javaBlock(javaCall, b == 0 ? JAVA_FIRST_WARMUP : JAVA_WARMUP);
      phpAll.addAll(p);
      javaAll.addAll(j);
      blocks.add(fmt("bloc %d : PHP %.1f ms, Java %.1f ms", b + 1, median(p), median(j)));
    }
    double phpMedian = median(phpAll);
    double javaMedian = median(javaAll);
    double ratio = javaMedian / phpMedian;
    String report =
        fmt(
            "B1 %s — médiane identity %.1f ms, médiane PHP %.1f ms, rapport %.2f (seuil %.2f ;"
                + " n = %d par implémentation ; %s)",
            what, javaMedian, phpMedian, ratio, MAX_RATIO, MEASURED, String.join(" ; ", blocks));
    System.out.println(report);
    assertThat(javaMedian).as(report).isLessThanOrEqualTo(MAX_RATIO * phpMedian);
  }

  private static List<Double> phpBlock(String op, String hash) throws Exception {
    List<Double> ms = new ArrayList<>();
    for (String line : phpOut(op, PHP_WARMUP, PER_BLOCK, hash)) {
      ms.add(Long.parseLong(line.trim()) / 1_000_000.0);
    }
    assertThat(ms).hasSize(PER_BLOCK);
    return ms;
  }

  private static List<Double> javaBlock(BooleanSupplier call, int warmup) {
    for (int i = 0; i < warmup; i++) {
      assertThat(call.getAsBoolean()).isTrue();
    }
    List<Double> ms = new ArrayList<>();
    for (int i = 0; i < PER_BLOCK; i++) {
      long t0 = System.nanoTime();
      boolean ok = call.getAsBoolean();
      long t = System.nanoTime() - t0;
      assertThat(ok).isTrue();
      ms.add(t / 1_000_000.0);
    }
    return ms;
  }

  private static List<String> phpOut(String op, int warmup, int n, String hash) throws Exception {
    ExecResult r =
        php.execInContainer(
            "php", SCRIPT_PATH, op, Integer.toString(warmup), Integer.toString(n), PASSWORD, hash);
    assertThat(r.getExitCode()).as("php %s : %s", op, r.getStderr()).isZero();
    return r.getStdout().lines().filter(l -> !l.isBlank()).toList();
  }

  static double median(List<Double> values) {
    List<Double> s = new ArrayList<>(values);
    Collections.sort(s);
    int n = s.size();
    return (s.get(n / 2) + s.get((n - 1) / 2)) / 2.0;
  }

  private static String fmt(String format, Object... args) {
    return String.format(Locale.ROOT, format, args);
  }
}
