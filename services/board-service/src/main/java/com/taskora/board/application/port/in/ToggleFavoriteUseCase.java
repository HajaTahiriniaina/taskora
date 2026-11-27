package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Bascule du favori propre à l'appelant (SC-BOARD-008). */
public interface ToggleFavoriteUseCase {

  /**
   * Bascule {@code board_member.favorite} de l'appelant ; le corps n'est jamais lu.
   *
   * @param caller appelant
   * @param board tableau
   * @return nouvel état
   */
  boolean toggle(UserId caller, BoardId board);
}
