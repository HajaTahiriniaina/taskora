package com.taskora.board.domain.exception;

import java.io.Serial;

/**
 * 404 {@code Ce tableau n’est pas dans la corbeille.} : routes {@code /api/trash/boards/{id}/**}.
 */
public class NotInTrashException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /** Texte exact du contrat (apostrophe typographique U+2019). */
  public static final String MESSAGE = "Ce tableau n’est pas dans la corbeille.";

  /** Crée l'exception avec le message du contrat. */
  public NotInTrashException() {
    super(MESSAGE);
  }
}
