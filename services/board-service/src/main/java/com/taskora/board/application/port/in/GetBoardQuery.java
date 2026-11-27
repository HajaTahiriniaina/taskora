package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Coquille d'un tableau (SC-BOARD-003). */
public interface GetBoardQuery {

  /**
   * Coquille complète, après la politique d'accès.
   *
   * @param caller appelant
   * @param board tableau
   * @return coquille
   */
  BoardView getBoard(UserId caller, BoardId board);
}
