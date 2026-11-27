package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.UserId;
import java.util.List;

/** Accueil : tableaux ouverts dont l'appelant est membre (SC-BOARD-002). */
public interface ListMyBoardsQuery {

  /**
   * Tableaux de l'appelant, triés {@code created_at DESC, id DESC}.
   *
   * @param caller appelant
   * @return résumés
   */
  List<BoardSummaryView> listMyBoards(UserId caller);
}
