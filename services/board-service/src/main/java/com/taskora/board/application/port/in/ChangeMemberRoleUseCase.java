package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Changement de rôle (SC-BOARD-014, 032). */
public interface ChangeMemberRoleUseCase {

  /**
   * Accès gestionnaire, membre visé (E28), propriétaire visé (403), puis corps et rôle.
   *
   * @param caller appelant
   * @param board tableau
   * @param target membre visé
   * @param rawBody corps brut
   * @return membre modifié
   */
  MemberView changeRole(UserId caller, BoardId board, UserId target, byte[] rawBody);
}
