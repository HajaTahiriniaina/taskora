package com.taskora.board.adapter.in.cli;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * Commandes d'exploitation (board-migration.md §8) : {@code --board.verify=baseline|check} avec
 * {@code --board.verify.baseline-file=<chemin>}, et {@code --board.outbox-drain}. Sortie : des
 * nombres seulement. Code de sortie : 0 vert ; 2 invariant violé ou écart en hausse ; 1 erreur
 * technique. Sans option : ne fait rien (démarrage normal du service).
 */
public class BoardCliRunner implements ApplicationRunner, ExitCodeGenerator {
  private final VerifyBoardInvariantsUseCase verify;
  private final DeliverPendingMessagesUseCase relay;

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
    throw new UnsupportedOperationException("BackCodeur " + verify + relay);
  }

  @Override
  public int getExitCode() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
