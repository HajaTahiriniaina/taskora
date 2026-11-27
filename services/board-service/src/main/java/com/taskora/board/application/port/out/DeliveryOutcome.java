package com.taskora.board.application.port.out;

/** Issue d'une livraison à Symfony. */
public enum DeliveryOutcome {
  /** 2xx. */
  DELIVERED,
  /** 400, 401, 409 : non rejouable. */
  PERMANENT_FAILURE,
  /** 404, 5xx, délai, réseau : rejouable. */
  RETRYABLE_FAILURE
}
