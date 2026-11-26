package com.taskora.identity.application.port.out;

/** Résultat d'un appel à l'endpoint interne Symfony. */
public enum ClaimOutcome {
  DELIVERED,
  RETRYABLE_FAILURE,
  PERMANENT_FAILURE
}
