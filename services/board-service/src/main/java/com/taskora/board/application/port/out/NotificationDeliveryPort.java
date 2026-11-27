package com.taskora.board.application.port.out;

/** Livraison d'une notification à {@code POST /internal/notifications} (Symfony, U12). */
public interface NotificationDeliveryPort {

  /**
   * Envoie la charge telle quelle avec le secret {@code INTERNAL_SECRET_BOARD_TO_SYMFONY}.
   *
   * @param payloadJson charge {@code NotificationRequested}
   * @return issue
   */
  DeliveryOutcome deliver(String payloadJson);
}
