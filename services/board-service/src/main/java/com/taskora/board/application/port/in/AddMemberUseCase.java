package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Ajout d'un compte existant ou invitation d'un e-mail (SC-BOARD-009 à 011). */
public interface AddMemberUseCase {

  /**
   * Accès gestionnaire, puis corps, puis ajout (201) ou invitation (202).
   *
   * @param caller appelant
   * @param board tableau
   * @param rawBody corps brut
   * @return résultat
   */
  AddMemberResult add(UserId caller, BoardId board, byte[] rawBody);
}
