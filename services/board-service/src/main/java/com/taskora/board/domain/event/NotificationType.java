package com.taskora.board.domain.event;

/** Types de notification ({@code NotificationType} de board-service.internal.yaml). */
public enum NotificationType {
  /** Ajout à un tableau (direct ou consommation d'invitation). */
  BOARD_INVITE,
  /** Arrivée d'un membre, pour les autres membres. */
  MEMBER_JOINED,
  /** Retrait par un gestionnaire (sans tableau associé). */
  MEMBER_REMOVED;

  /**
   * Valeur du contrat ({@code board_invite}…).
   *
   * @return valeur en minuscules
   */
  public String value() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
