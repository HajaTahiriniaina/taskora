package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Retrait d'un membre ou départ volontaire (SC-BOARD-015, 016, 033). */
public interface RemoveMemberUseCase {

  /**
   * Désassigne le membre des cartes du tableau puis supprime l'adhésion, dans une transaction.
   *
   * @param caller appelant
   * @param board tableau
   * @param target membre visé (l'appelant pour un départ)
   */
  void remove(UserId caller, BoardId board, UserId target);
}
