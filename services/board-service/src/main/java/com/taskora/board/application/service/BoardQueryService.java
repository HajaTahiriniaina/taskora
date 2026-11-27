package com.taskora.board.application.service;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.GetBoardQuery;
import com.taskora.board.application.port.in.ListMyBoardsQuery;
import com.taskora.board.application.port.in.ListTrashedBoardsQuery;
import com.taskora.board.application.port.in.TrashedBoardView;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.util.List;

/** Lectures de T1 : accueil, coquille, corbeille (B-ADR-8). Squelette : BackCodeur l'assemble. */
public class BoardQueryService implements ListMyBoardsQuery, GetBoardQuery, ListTrashedBoardsQuery {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public BoardQueryService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public List<BoardSummaryView> listMyBoards(UserId caller) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public BoardView getBoard(UserId caller, BoardId board) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public List<TrashedBoardView> listTrashedBoards(UserId caller) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
