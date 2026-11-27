package com.taskora.board.domain.exception;

import java.io.Serial;

/** 404 {@code Ce tableau est dans la corbeille.} : membre d'un tableau dans la corbeille. */
public class BoardInTrashException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /** Texte exact du contrat. */
  public static final String MESSAGE = "Ce tableau est dans la corbeille.";

  /** Crée l'exception avec le message du contrat. */
  public BoardInTrashException() {
    super(MESSAGE);
  }
}
