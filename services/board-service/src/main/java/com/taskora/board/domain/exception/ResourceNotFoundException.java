package com.taskora.board.domain.exception;

import java.io.Serial;

/**
 * 404 sur une ressource : {@code Ressource introuvable.} (E12, E28) ou message précis ({@code
 * Membre introuvable.}).
 */
public class ResourceNotFoundException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /** Message par défaut. */
  public static final String MESSAGE = "Ressource introuvable.";

  /** Crée l'exception avec le message par défaut. */
  public ResourceNotFoundException() {
    super(MESSAGE);
  }

  /**
   * Crée l'exception avec un message précis.
   *
   * @param message texte exact du contrat
   */
  public ResourceNotFoundException(String message) {
    super(message);
  }
}
