package com.taskora.identity.domain.event;

import com.taskora.identity.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/** Événement de domaine d'identity-service. */
public sealed interface DomainEvent permits UserRegistered, UserProfileUpdated {
  UUID eventId();

  Instant occurredAt();

  UserId userId();
}
