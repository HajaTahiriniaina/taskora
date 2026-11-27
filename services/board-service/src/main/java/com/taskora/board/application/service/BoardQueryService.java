package com.taskora.board.application.service;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.GetBoardQuery;
import com.taskora.board.application.port.in.ListMyBoardsQuery;
import com.taskora.board.application.port.in.ListTrashedBoardsQuery;
import com.taskora.board.application.port.in.TrashedBoardView;
import com.taskora.board.application.port.out.BoardAccessQueryPort;
import com.taskora.board.application.port.out.BoardReadModelPort;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.policy.BoardAccessPolicy;
import java.util.List;

/**
 * Lectures (B-ADR-8) : accueil, coquille, corbeille. Nombre de requêtes constant ; la coquille
 * passe par la politique d'accès (une requête) avant toute lecture.
 */
public class BoardQueryService implements ListMyBoardsQuery, GetBoardQuery, ListTrashedBoardsQuery {
  private final UnitOfWorkPort unitOfWork;
  private final BoardAccessQueryPort access;
  private final BoardReadModelPort reads;
  private final BoardAccessPolicy policy = new BoardAccessPolicy();

  /**
   * Assemble les lectures.
   *
   * @param unitOfWork transaction de lecture (instantané cohérent)
   * @param access données d'accès
   * @param reads modèles de lecture
   */
  public BoardQueryService(
      UnitOfWorkPort unitOfWork, BoardAccessQueryPort access, BoardReadModelPort reads) {
    this.unitOfWork = unitOfWork;
    this.access = access;
    this.reads = reads;
  }

  @Override
  public List<BoardSummaryView> listMyBoards(UserId caller) {
    return unitOfWork.inTransaction(() -> reads.boardsOf(caller));
  }

  @Override
  public BoardView getBoard(UserId caller, BoardId board) {
    return unitOfWork.inTransaction(() -> {
      policy.requireMember(access.load(board, caller));
      return reads.board(board, caller);
    });
  }

  @Override
  public List<TrashedBoardView> listTrashedBoards(UserId caller) {
    return unitOfWork.inTransaction(() -> reads.trashedBoardsOwnedBy(caller));
  }
}
