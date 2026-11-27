package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Annulation d'une invitation (SC-BOARD-012, E28). */
public interface CancelInvitationUseCase {

  /**
   * Accès gestionnaire ; invitation inexistante ou d'un autre tableau → 404.
   *
   * @param caller appelant
   * @param board tableau
   * @param invitationId invitation
   */
  void cancel(UserId caller, BoardId board, int invitationId);
}
