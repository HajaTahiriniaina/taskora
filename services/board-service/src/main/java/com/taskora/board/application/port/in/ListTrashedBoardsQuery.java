package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.UserId;
import java.util.List;

/** Corbeille des tableaux de l'appelant (SC-TRASH-007). */
public interface ListTrashedBoardsQuery {

  /**
   * Tableaux dans la corbeille dont l'appelant est propriétaire, triés {@code deleted_at DESC, id
   * DESC}.
   *
   * @param caller appelant
   * @return tableaux
   */
  List<TrashedBoardView> listTrashedBoards(UserId caller);
}
