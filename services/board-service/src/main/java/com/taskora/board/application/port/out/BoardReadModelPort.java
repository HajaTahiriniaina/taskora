package com.taskora.board.application.port.out;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.MemberView;
import com.taskora.board.application.port.in.TrashedBoardView;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.util.List;

/** Modèles de lecture JDBC, nombre de requêtes constant (B-ADR-8). */
public interface BoardReadModelPort {

  /**
   * Tableaux ouverts d'un membre avec membres et compteurs (au plus 4 requêtes).
   *
   * @param viewer appelant
   * @return résumés triés {@code created_at DESC, id DESC}
   */
  List<BoardSummaryView> boardsOf(UserId viewer);

  /**
   * Coquille d'un tableau (au plus 9 requêtes, accès compris).
   *
   * @param board tableau
   * @param viewer appelant
   * @return coquille
   */
  BoardView board(BoardId board, UserId viewer);

  /**
   * Résumé sans compteurs (réponse de {@code PATCH}, FA11).
   *
   * @param board tableau
   * @param viewer appelant
   * @return résumé avec {@code listCount} et {@code cardCount} à 0
   */
  BoardSummaryView summaryWithoutCounters(BoardId board, UserId viewer);

  /**
   * Membre exposé.
   *
   * @param board tableau
   * @param user membre
   * @return membre
   */
  MemberView member(BoardId board, UserId user);

  /**
   * Corbeille du propriétaire.
   *
   * @param owner propriétaire
   * @return tableaux triés {@code deleted_at DESC, id DESC}
   */
  List<TrashedBoardView> trashedBoardsOwnedBy(UserId owner);
}
