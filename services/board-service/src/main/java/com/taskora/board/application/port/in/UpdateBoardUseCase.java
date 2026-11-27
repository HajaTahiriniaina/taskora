package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Modification d'un tableau par un membre (SC-BOARD-005). */
public interface UpdateBoardUseCase {

  /**
   * Accès, puis analyse du corps, puis mise à jour des seules colonnes présentes.
   *
   * @param caller appelant
   * @param board tableau
   * @param rawBody corps brut
   * @return résumé avec compteurs à 0 (FA11)
   */
  BoardSummaryView update(UserId caller, BoardId board, byte[] rawBody);
}
