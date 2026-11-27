package com.taskora.board.domain.exception;

import java.io.Serial;

/** 404 {@code Tableau introuvable.} : tableau inexistant, ou appelant non membre (B-ADR-3). */
public class BoardNotFoundException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /** Texte exact du contrat. */
  public static final String MESSAGE = "Tableau introuvable.";

  /** Crée l'exception avec le message du contrat. */
  public BoardNotFoundException() {
    super(MESSAGE);
  }
}
