package com.taskora.identity.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SC-IDENT-023 (A14, identity-migration.md §3, §4, §6.2) : commandes --identity.legacy-import=run|
 * verify et --identity.outbox-drain, codes de sortie, sortie limitée à des nombres et empreintes.
 */
@ExtendWith(OutputCaptureExtension.class)
class LegacyImportRunnerTest {
  private static final Map<String, String> METRICS =
      Map.of(
          "source.count", "9", "target.count", "9", "source.crc", "123456", "target.crc", "123456");

  private ImportLegacyUsersUseCase importer;
  private DeliverPendingMessagesUseCase relay;
  private LegacyImportRunner runner;

  @BeforeEach
  void setUp() {
    importer = mock(ImportLegacyUsersUseCase.class);
    relay = mock(DeliverPendingMessagesUseCase.class);
    runner = new LegacyImportRunner(importer, relay);
  }

  private void run(String... args) throws Exception {
    runner.run(new DefaultApplicationArguments(args));
  }

  private static void assertNoPersonalData(CapturedOutput output) {
    assertThat(output.getAll())
        .doesNotContain("@")
        .doesNotContain("$2y$")
        .doesNotContain("password");
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — sans option : ne fait rien, code de sortie 0 (démarrage normal du service)")
  void noOption() throws Exception {
    run();
    run("--server.port=8080");
    verifyNoInteractions(importer, relay);
    assertThat(runner.getExitCode()).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-023 — run cohérent : copie puis contrôles, code 0, rapport chiffré")
  void runConsistent(CapturedOutput output) throws Exception {
    when(importer.importAll()).thenReturn(new ImportReport(9, 7, 1, 1, 0));
    when(importer.verify()).thenReturn(new VerificationReport(true, List.of(), METRICS));
    run("--identity.legacy-import=run");
    verify(importer).importAll();
    verify(importer).verify();
    assertThat(runner.getExitCode()).isZero();
    assertThat(output.getAll())
        .contains("source=9", "insérés=7", "mis à jour=1", "inchangés=1", "cible seule=0")
        .contains("Contrôles de reprise : cohérents")
        .contains("source.crc = 123456");
    assertNoPersonalData(output);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — run puis contrôles incohérents : code de sortie 1, contrôles en échec nommés")
  void runInconsistent(CapturedOutput output) throws Exception {
    when(importer.importAll()).thenReturn(new ImportReport(9, 9, 0, 0, 0));
    when(importer.verify())
        .thenReturn(
            new VerificationReport(false, List.of("count", "targetEmailConflicts"), METRICS));
    run("--identity.legacy-import=run");
    assertThat(runner.getExitCode()).isEqualTo(1);
    assertThat(output.getAll())
        .contains("Contrôles de reprise en échec")
        .contains("targetEmailConflicts");
    assertNoPersonalData(output);
  }

  @Test
  @DisplayName("SC-IDENT-023 — verify seul : aucune copie, code 0 si cohérent")
  void verifyOnlyConsistent() throws Exception {
    when(importer.verify()).thenReturn(new VerificationReport(true, List.of(), METRICS));
    run("--identity.legacy-import=verify");
    verify(importer, never()).importAll();
    assertThat(runner.getExitCode()).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-023 — verify seul incohérent : code 1, aucune copie")
  void verifyOnlyInconsistent(CapturedOutput output) throws Exception {
    when(importer.verify()).thenReturn(new VerificationReport(false, List.of("count"), METRICS));
    run("--identity.legacy-import=verify");
    verify(importer, never()).importAll();
    assertThat(runner.getExitCode()).isEqualTo(1);
    assertNoPersonalData(output);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 A18 — contrôle préalable en échec (doublon ou conflit d'e-mail cible) : exception propagée, pas de contrôles, message sans e-mail")
  void preCheckFailurePropagates(CapturedOutput output) {
    IllegalStateException refused =
        new IllegalStateException(
            "Reprise annulée avant toute écriture : 1 compte(s) source dont l'e-mail normalisé"
                + " existe dans la cible sous un autre id");
    when(importer.importAll()).thenThrow(refused);
    assertThatThrownBy(() -> run("--identity.legacy-import=run")).isSameAs(refused);
    verify(importer, never()).verify();
    assertThat(refused.getMessage()).doesNotContain("@");
    assertNoPersonalData(output);
  }

  @ParameterizedTest(name = "« {0} »")
  @ValueSource(
      strings = {
        "--identity.legacy-import=foo",
        "--identity.legacy-import",
        "--identity.legacy-import="
      })
  @DisplayName("SC-IDENT-023 — mode inconnu ou absent : code de sortie 2, rien n'est lancé")
  void unknownMode(String arg, CapturedOutput output) throws Exception {
    run(arg);
    verifyNoInteractions(importer, relay);
    assertThat(runner.getExitCode()).isEqualTo(2);
    assertThat(output.getAll()).contains("--identity.legacy-import attend run ou verify");
  }

  @Test
  @DisplayName("SC-IDENT-023 — deux modes à la fois : refusé (code 2)")
  void twoModes() throws Exception {
    run("--identity.legacy-import=run", "--identity.legacy-import=verify");
    verifyNoInteractions(importer);
    assertThat(runner.getExitCode()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "identity-migration.md §6.2 — --identity.outbox-drain : lots de 20 jusqu'à épuisement, total journalisé, code 0")
  void outboxDrain(CapturedOutput output) throws Exception {
    when(relay.deliverDue(20)).thenReturn(20, 3, 0);
    run("--identity.outbox-drain");
    verify(relay, times(3)).deliverDue(20);
    verifyNoInteractions(importer);
    assertThat(runner.getExitCode()).isZero();
    assertThat(output.getAll()).contains("Outbox : 23 message(s) traité(s)");
    assertNoPersonalData(output);
  }

  @Test
  @DisplayName(
      "identity-migration.md §6.2 — --identity.outbox-drain sur une outbox vide : un seul lot")
  void outboxDrainEmpty(CapturedOutput output) throws Exception {
    when(relay.deliverDue(anyInt())).thenReturn(0);
    run("--identity.outbox-drain");
    verify(relay, times(1)).deliverDue(20);
    assertThat(output.getAll()).contains("Outbox : 0 message(s) traité(s)");
  }

  @Test
  @DisplayName("SC-IDENT-023 — la reprise est prioritaire si les deux options sont données")
  void importTakesPrecedence() throws Exception {
    when(importer.verify()).thenReturn(new VerificationReport(true, List.of(), METRICS));
    run("--identity.legacy-import=verify", "--identity.outbox-drain");
    verify(importer).verify();
    verifyNoInteractions(relay);
  }
}
