package com.taskora.board.adapter.in.cli;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.application.port.in.InvariantReport;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commandes d'exploitation (board-migration.md §8) : {@code --board.verify=baseline|check} avec
 * {@code --board.verify.baseline-file=<chemin>}, et {@code --board.outbox-drain}. Sortie : des
 * nombres seulement. Code de sortie : 0 vert ; 2 invariant violé ou écart en hausse ; 1 erreur
 * technique. Sans option : ne fait rien (démarrage normal du service).
 */
public class BoardCliRunner implements ApplicationRunner, ExitCodeGenerator {
  static final String VERIFY = "board.verify";
  static final String BASELINE_FILE = "board.verify.baseline-file";
  static final String DRAIN = "board.outbox-drain";
  static final int OK = 0;
  static final int TECHNICAL_ERROR = 1;
  static final int REGRESSED = 2;
  private static final int DRAIN_BATCH = 50;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final VerifyBoardInvariantsUseCase verify;
  private final DeliverPendingMessagesUseCase relay;
  private volatile int exitCode = OK;

  /**
   * Crée le lanceur.
   *
   * @param verify commande verify
   * @param relay relais de l'outbox
   */
  public BoardCliRunner(VerifyBoardInvariantsUseCase verify, DeliverPendingMessagesUseCase relay) {
    this.verify = verify;
    this.relay = relay;
  }

  @Override
  public void run(ApplicationArguments args) {
    exitCode = OK;
    try {
      if (args.containsOption(VERIFY)) {
        exitCode = verify(single(args, VERIFY), single(args, BASELINE_FILE));
      } else if (args.containsOption(DRAIN)) {
        int total = 0;
        int processed;
        do {
          processed = relay.deliverDue(DRAIN_BATCH);
          total += processed;
        } while (processed == DRAIN_BATCH);
        IO.println("outbox.processed=" + total);
      }
    } catch (IOException | RuntimeException e) {
      IO.println("verify.error=" + e.getClass().getSimpleName());
      exitCode = TECHNICAL_ERROR;
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private int verify(String mode, String file) throws IOException {
    Path baseline = Path.of(file);
    InvariantReport current = verify.measure();
    print(current);
    if ("baseline".equals(mode)) {
      Files.writeString(baseline, JSON.writeValueAsString(current.counters()));
      return OK;
    }
    if (!"check".equals(mode)) {
      throw new IllegalArgumentException("mode");
    }
    InvariantReport initial = read(baseline);
    boolean regressed = verify.regressed(initial, current);
    IO.println("verify.regressed=" + (regressed ? 1 : 0));
    return regressed ? REGRESSED : OK;
  }

  private static InvariantReport read(Path baseline) throws IOException {
    JsonNode root;
    try {
      root = JSON.readTree(Files.readString(baseline));
    } catch (JacksonException e) {
      throw new IOException("état initial illisible", e);
    }
    Map<String, Long> counters = new TreeMap<>();
    root.properties().forEach(e -> counters.put(e.getKey(), e.getValue().asLong()));
    return new InvariantReport(counters);
  }

  private static void print(InvariantReport report) {
    report.counters().forEach((name, value) -> IO.println(name + "=" + value));
  }

  private static String single(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null || values.size() != 1) {
      throw new IllegalArgumentException(name);
    }
    return values.getFirst();
  }
}
