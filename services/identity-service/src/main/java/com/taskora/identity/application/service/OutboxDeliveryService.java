package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.out.ClaimOutcome;
import com.taskora.identity.application.port.out.InvitationClaimPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PendingClaim;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Relais de l'outbox vers Symfony (A10 : backoff 5 s, 10 s, 20 s… plafonné à 5 min). */
public class OutboxDeliveryService implements DeliverPendingMessagesUseCase {
  static final Duration FIRST_DELAY = Duration.ofSeconds(5);
  static final Duration MAX_DELAY = Duration.ofMinutes(5);
  static final int STUCK_ATTEMPTS = 20;
  static final String RETRYABLE = "RETRYABLE_FAILURE";
  static final String PERMANENT = "PERMANENT_FAILURE";

  private static final System.Logger LOG = System.getLogger(OutboxDeliveryService.class.getName());

  private final OutboxPort outbox;
  private final InvitationClaimPort client;
  private final UnitOfWorkPort unitOfWork;
  private final Clock clock;

  /**
   * Assemble le relais.
   *
   * @param outbox messages d'outbox
   * @param client client de l'endpoint Symfony
   * @param unitOfWork transaction tenant les verrous du lot
   * @param clock horloge des échéances
   */
  public OutboxDeliveryService(
      OutboxPort outbox, InvitationClaimPort client, UnitOfWorkPort unitOfWork, Clock clock) {
    this.outbox = outbox;
    this.client = client;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** Renvoie le nombre de messages traités (livrés ou replanifiés) dans ce lot. */
  @Override
  public int deliverDue(int batchSize) {
    Instant now = clock.instant();
    return unitOfWork.inTransaction(() -> {
      // Les verrous (FOR UPDATE SKIP LOCKED) sont tenus jusqu'à la fin du lot.
      List<PendingClaim> due = outbox.lockDueClaims(now, batchSize);
      for (PendingClaim claim : due) {
        deliver(claim, now);
      }
      return due.size();
    });
  }

  @Override
  public boolean deliverNow(UUID eventId) {
    Instant now = clock.instant();
    return unitOfWork.inTransaction(() ->
        outbox
            .lockClaim(eventId)
            .map(claim -> deliver(claim, now) == ClaimOutcome.DELIVERED)
            .orElse(false));
  }

  private ClaimOutcome deliver(PendingClaim pending, Instant now) {
    UUID eventId = pending.claim().eventId();
    ClaimOutcome outcome;
    String errorCode;
    try {
      outcome = client.claim(pending.claim());
      errorCode = outcome.name();
    } catch (RuntimeException e) {
      outcome = ClaimOutcome.RETRYABLE_FAILURE;
      errorCode = RETRYABLE + ":" + e.getClass().getSimpleName();
    }
    switch (outcome) {
      case DELIVERED -> outbox.markDelivered(eventId, now);
      case PERMANENT_FAILURE -> {
        outbox.markFailed(eventId, PERMANENT);
        LOG.log(
            System.Logger.Level.ERROR,
            "Message d''outbox {0} refusé définitivement par Symfony : intervention manuelle",
            eventId);
      }
      case RETRYABLE_FAILURE -> {
        int attempts = pending.attempts() + 1;
        outbox.markRetry(eventId, attempts, now.plus(backoff(pending.attempts())), errorCode);
        if (attempts > STUCK_ATTEMPTS) {
          LOG.log(
              System.Logger.Level.WARNING,
              "Message d''outbox {0} toujours en attente après {1} tentatives",
              eventId,
              attempts);
        }
      }
      default -> throw new IllegalStateException("Résultat de livraison inconnu : " + outcome);
    }
    return outcome;
  }

  /** 5 s × 2^tentatives précédentes, plafonné à 5 min. */
  static Duration backoff(int previousAttempts) {
    if (previousAttempts >= 6) { // 5 s × 2^6 = 320 s > 5 min
      return MAX_DELAY;
    }
    return FIRST_DELAY.multipliedBy(1L << previousAttempts);
  }
}
