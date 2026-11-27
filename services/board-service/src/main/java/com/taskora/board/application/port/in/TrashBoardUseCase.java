package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Mise à la corbeille par le propriétaire (SC-BOARD-007). */
public interface TrashBoardUseCase {

  /**
   * Accès propriétaire, puis {@code deleted_at}/{@code deleted_by_id}.
   *
   * @param caller appelant
   * @param board tableau
   */
  void trash(UserId caller, BoardId board);
}
