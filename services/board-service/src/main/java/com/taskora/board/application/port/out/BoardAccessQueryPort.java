package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.policy.BoardAccessView;

/** Chargement des données d'accès par une seule requête (B-ADR-3 point 1). */
public interface BoardAccessQueryPort {

  /**
   * Données d'accès d'un appelant à un tableau.
   *
   * @param board tableau
   * @param caller appelant
   * @return vue d'accès ({@link BoardAccessView#missing(BoardId)} si le tableau n'existe pas)
   */
  BoardAccessView load(BoardId board, UserId caller);
}
