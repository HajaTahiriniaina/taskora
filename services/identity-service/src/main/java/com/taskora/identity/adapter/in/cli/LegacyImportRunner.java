package com.taskora.identity.adapter.in.cli;

import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * Commandes ponctuelles (A14, identity-migration.md §3, §6.2) :
 *
 * <ul>
 *   <li>{@code --identity.legacy-import=run} : copie idempotente puis contrôles ;
 *   <li>{@code --identity.legacy-import=verify} : contrôles seuls ;
 *   <li>{@code --identity.outbox-drain} : livre les messages d'invitation dus puis s'arrête.
 * </ul>
 *
 * Code de sortie ≠ 0 si les contrôles sont incohérents. La sortie ne contient que des nombres et
 * des empreintes.
 */
public class LegacyImportRunner implements ApplicationRunner, ExitCodeGenerator {
  public static final String IMPORT_OPTION = "identity.legacy-import";
  public static final String DRAIN_OPTION = "identity.outbox-drain";
  private static final Logger LOG = LoggerFactory.getLogger(LegacyImportRunner.class);
  private static final int BATCH_SIZE = 20;

  private final ImportLegacyUsersUseCase importer;
  private final DeliverPendingMessagesUseCase relay;
  private int exitCode;

  public LegacyImportRunner(
      ImportLegacyUsersUseCase importer, DeliverPendingMessagesUseCase relay) {
    this.importer = importer;
    this.relay = relay;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (args.containsOption(IMPORT_OPTION)) {
      String mode = String.join(",", args.getOptionValues(IMPORT_OPTION));
      exitCode = switch (mode) {
        case "run" -> runImport();
        case "verify" -> verify();
        default -> {
          LOG.error("--{} attend run ou verify", IMPORT_OPTION);
          yield 2;
        }
      };
    } else if (args.containsOption(DRAIN_OPTION)) {
      int total = 0;
      int processed;
      do {
        processed = relay.deliverDue(BATCH_SIZE);
        total += processed;
      } while (processed > 0);
      LOG.info("Outbox : {} message(s) traité(s)", total);
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private int runImport() {
    ImportReport report = importer.importAll();
    LOG.info(
        "Reprise : source={} insérés={} mis à jour={} inchangés={} cible seule={}",
        report.sourceRows(),
        report.inserted(),
        report.updated(),
        report.unchanged(),
        report.targetOnly());
    return verify();
  }

  private int verify() {
    VerificationReport report = importer.verify();
    Map<String, String> sorted = new TreeMap<>(report.metrics());
    sorted.forEach((key, value) -> LOG.info("Contrôle {} = {}", key, value));
    if (report.consistent()) {
      LOG.info("Contrôles de reprise : cohérents");
      return 0;
    }
    LOG.error("Contrôles de reprise en échec : {}", report.failedChecks());
    return 1;
  }
}
