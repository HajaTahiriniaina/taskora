package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Restauration d'un tableau par son propriétaire (SC-TRASH-007). */
public interface RestoreBoardUseCase {

  /**
   * Politique de la corbeille, puis restauration.
   *
   * @param caller appelant
   * @param board tableau
   * @return tableau restauré
   */
  BoardId restore(UserId caller, BoardId board);
}
