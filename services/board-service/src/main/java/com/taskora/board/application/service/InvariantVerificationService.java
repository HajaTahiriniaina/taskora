package com.taskora.board.application.service;

import com.taskora.board.application.port.in.InvariantReport;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;
import com.taskora.board.application.port.out.InvariantsQueryPort;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import java.util.Map;
import java.util.TreeMap;

/**
 * Commande {@code verify} (spec §4.6, board-migration.md §8) : compteurs V1 à V8, lecture seule ;
 * un écart hérité est toléré tant qu'il n'augmente pas.
 */
public class InvariantVerificationService implements VerifyBoardInvariantsUseCase {
  private final InvariantsQueryPort invariants;
  private final UnitOfWorkPort unitOfWork;

  /**
   * Assemble la commande.
   *
   * @param invariants requêtes de comptage
   * @param unitOfWork transaction de lecture (instantané cohérent)
   */
  public InvariantVerificationService(InvariantsQueryPort invariants, UnitOfWorkPort unitOfWork) {
    this.invariants = invariants;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public InvariantReport measure() {
    return new InvariantReport(
        new TreeMap<>(unitOfWork.inTransaction(invariants::countViolations)));
  }

  @Override
  public boolean regressed(InvariantReport baseline, InvariantReport current) {
    for (Map.Entry<String, Long> counter : current.counters().entrySet()) {
      if (counter.getValue() > baseline.counters().getOrDefault(counter.getKey(), 0L)) {
        return true;
      }
    }
    return false;
  }
}
