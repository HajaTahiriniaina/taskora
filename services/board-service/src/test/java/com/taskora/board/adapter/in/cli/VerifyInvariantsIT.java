package com.taskora.board.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.application.port.in.InvariantReport;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;
import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * board-migration.md §8 — {@code verify} : V1 à V8 sur un jeu sain (tout à 0) et sur des jeux
 * corrompus ; {@code --board.verify=baseline} puis {@code check} ; codes de sortie 0 / 2 / 1 ;
 * sortie = nombres seulement ; lecture seule (aucune écriture en base).
 */
@ExtendWith(OutputCaptureExtension.class)
class VerifyInvariantsIT extends ApiTestBase {
  @TempDir Path dir;

  private VerifyBoardInvariantsUseCase verify() {
    return bean(VerifyBoardInvariantsUseCase.class);
  }

  private int run(String... args) {
    BoardCliRunner runner = bean(BoardCliRunner.class);
    try {
      runner.run(new DefaultApplicationArguments(args));
    } catch (Exception e) {
      // le code de sortie dit l'erreur technique
    }
    return runner.getExitCode();
  }

  private static long sum(InvariantReport r, String prefix) {
    return r.counters().entrySet().stream()
        .filter(e -> e.getKey().startsWith(prefix))
        .mapToLong(Map.Entry::getValue)
        .sum();
  }

  @Test
  @DisplayName("§8 — jeu de référence sain : tous les compteurs V1 à V8 à 0, chacun présent")
  void healthy() {
    InvariantReport r = verify().measure();
    for (int v = 1; v <= 8; v++) {
      String prefix = "V" + v;
      assertThat(r.counters().keySet())
          .as("compteur %s présent", prefix)
          .anyMatch(k -> k.startsWith(prefix));
    }
    assertThat(r.counters().keySet()).allMatch(k -> k.matches("V[1-8]([._].+)?"));
    assertThat(r.counters().values()).allMatch(n -> n == 0L);
  }

  static Stream<Arguments> corruptions() {
    return Stream.of(
        Arguments.of(
            "V1 — trou de positions", "V1", "UPDATE taskora.card SET position = 5 WHERE id = 402"),
        Arguments.of(
            "V2 — carte archivée hors -1",
            "V2",
            "UPDATE taskora.card SET position = 3 WHERE id = 404"),
        Arguments.of(
            "V3 — card_seq en retard",
            "V3",
            "UPDATE taskora.board SET card_seq = 2 WHERE id = 101"),
        Arguments.of(
            "V4 — deux propriétaires",
            "V4",
            "UPDATE taskora.board_member SET role = 'owner' WHERE board_id = 101 AND user_id = 2"),
        Arguments.of(
            "V5 — assigné non membre",
            "V5",
            "INSERT INTO taskora.card_assignee (card_id, user_id) VALUES (403, 5)"),
        Arguments.of(
            "V6 — étiquette d'un autre tableau",
            "V6",
            "INSERT INTO taskora.card_label (card_id, label_id) VALUES (402, 503)"),
        Arguments.of(
            "V7 — carte dans la liste d'un autre tableau",
            "V7",
            "UPDATE taskora.card SET list_id = 206 WHERE id = 403"),
        Arguments.of(
            "V8 — message FAILED",
            "V8",
            "INSERT INTO board.outbox_message (event_id, type, destination, aggregate_id, payload, occurred_at,"
                + " available_at, status, attempts) VALUES (UUID(), 'NotificationRequested', 'symfony-notifications',"
                + " 101, '{}', '2026-11-27 09:00:00', '2026-11-27 09:00:00', 'FAILED', 1)"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("corruptions")
  @DisplayName("§8 — jeu corrompu : le compteur concerné augmente, regressed vrai, check → code 2")
  void corrupted(String label, String invariant, String corruption) throws Exception {
    Path baseline = dir.resolve("baseline.json");
    assertThat(run("--board.verify=baseline", "--board.verify.baseline-file=" + baseline)).isZero();
    InvariantReport before = verify().measure();
    BoardDatabase.rootExecute(corruption);
    InvariantReport after = verify().measure();
    assertThat(sum(after, invariant)).as(label).isGreaterThan(sum(before, invariant));
    assertThat(verify().regressed(before, after)).isTrue();
    assertThat(run("--board.verify=check", "--board.verify.baseline-file=" + baseline))
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "§8 — écart hérité toléré tant qu'il n'augmente pas : baseline puis check → 0 ; hausse → 2")
  void inheritedDeviationTolerated() {
    BoardDatabase.rootExecute("UPDATE taskora.card SET position = 5 WHERE id = 402");
    Path baseline = dir.resolve("baseline.json");
    assertThat(run("--board.verify=baseline", "--board.verify.baseline-file=" + baseline)).isZero();
    assertThat(run("--board.verify=check", "--board.verify.baseline-file=" + baseline)).isZero();
    BoardDatabase.rootExecute("UPDATE taskora.card SET position = 7 WHERE id = 407");
    assertThat(run("--board.verify=check", "--board.verify.baseline-file=" + baseline))
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "§8 — baseline : fichier JSON de nombres seulement ; sortie console sans donnée (ni e-mail ni titre)")
  void outputIsNumbersOnly(CapturedOutput output) throws Exception {
    Path baseline = dir.resolve("baseline.json");
    assertThat(run("--board.verify=baseline", "--board.verify.baseline-file=" + baseline)).isZero();
    String file = Files.readString(baseline);
    assertThat(json(file).isObject()).isTrue();
    json(file)
        .properties()
        .forEach(e -> assertThat(e.getValue().isNumber()).as(e.getKey()).isTrue());
    assertThat(output.getAll())
        .doesNotContain("@exemple.test")
        .doesNotContain("Lancement produit")
        .doesNotContain("Alice");
    assertThat(file).doesNotContain("@").doesNotContain("Lancement");
  }

  @Test
  @DisplayName("§8 — check sans fichier d'état initial → code 1 (erreur technique)")
  void technicalError() {
    assertThat(
            run(
                "--board.verify=check",
                "--board.verify.baseline-file=" + dir.resolve("absent.json")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("§8 — lecture seule : aucune écriture en base pendant measure, baseline et check")
  void readOnly() {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    verify().measure();
    Path baseline = dir.resolve("baseline.json");
    run("--board.verify=baseline", "--board.verify.baseline-file=" + baseline);
    run("--board.verify=check", "--board.verify.baseline-file=" + baseline);
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName("§8 — sans option : le lanceur ne fait rien (démarrage normal), code 0")
  void noOption() {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertThat(run()).isZero();
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }
}
