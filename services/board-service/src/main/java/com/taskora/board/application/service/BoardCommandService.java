package com.taskora.board.application.service;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.CreateBoardUseCase;
import com.taskora.board.application.port.in.RestoreBoardUseCase;
import com.taskora.board.application.port.in.ToggleFavoriteUseCase;
import com.taskora.board.application.port.in.TrashBoardUseCase;
import com.taskora.board.application.port.in.UpdateBoardUseCase;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Écritures sur le tableau (création, modification, favori, corbeille). Squelette. */
public class BoardCommandService
    implements CreateBoardUseCase,
        UpdateBoardUseCase,
        TrashBoardUseCase,
        ToggleFavoriteUseCase,
        RestoreBoardUseCase {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public BoardCommandService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public BoardView create(UserId caller, byte[] rawBody) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public BoardSummaryView update(UserId caller, BoardId board, byte[] rawBody) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public void trash(UserId caller, BoardId board) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public boolean toggle(UserId caller, BoardId board) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public BoardId restore(UserId caller, BoardId board) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
