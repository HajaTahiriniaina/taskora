package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.event.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbox transactionnelle (A11). */
public interface OutboxPort {
  /** Statut HELD, destination broker. */
  void appendEvent(DomainEvent event);

  void appendInvitationClaim(InvitationClaim claim, Instant availableAt);

  List<PendingClaim> lockDueClaims(Instant now, int limit);

  Optional<PendingClaim> lockClaim(UUID eventId);

  void markDelivered(UUID eventId, Instant at);

  void markRetry(UUID eventId, int attempts, Instant nextAttemptAt, String errorCode);

  void markFailed(UUID eventId, String errorCode);
}
