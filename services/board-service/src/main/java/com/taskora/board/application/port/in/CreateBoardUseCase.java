package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.UserId;

/** Création d'un tableau avec son contenu par défaut (SC-BOARD-001). */
public interface CreateBoardUseCase {

  /**
   * Analyse le corps, crée le tableau, l'adhésion {@code owner} et le contenu par défaut dans une
   * transaction.
   *
   * @param caller appelant (propriétaire)
   * @param rawBody corps brut (vide = {@code {}})
   * @return coquille du tableau créé
   */
  BoardView create(UserId caller, byte[] rawBody);
}
