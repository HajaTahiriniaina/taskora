package com.taskora.identity.domain.event;

import com.taskora.identity.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/** Événement de domaine d'identity-service. */
public sealed interface DomainEvent permits UserRegistered, UserProfileUpdated {
  /**
   * Identifiant unique (UUID v4), repris comme event_id de l'outbox.
   *
   * @return identifiant de l'événement
   */
  UUID eventId();

  /**
   * Instant de l'événement (UTC).
   *
   * @return instant
   */
  Instant occurredAt();

  /**
   * Compte concerné.
   *
   * @return identifiant du compte
   */
  UserId userId();
}
