package com.taskora.board.application.service;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.application.port.out.DeliveryOutcome;
import com.taskora.board.application.port.out.NotificationDeliveryPort;
import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.PendingMessage;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Relais de l'outbox (B-ADR-5 point 2) : lot réservé dans une transaction courte ({@code FOR UPDATE
 * SKIP LOCKED} et bail), puis livré hors transaction (aucun appel réseau pendant un verrou) ; deux
 * relais ne livrent donc jamais le même message en même temps ; 200 → livré ; 400, 401, 409 → échec
 * définitif ({@code ERROR}, sans boucle) ; 404, 5xx, délai → nouvel essai après 5 s, doublé à
 * chaque échec, plafonné à 5 min. Seuls les messages {@code PENDING} sont livrés ({@code HELD}
 * jamais).
 */
public class OutboxDeliveryService implements DeliverPendingMessagesUseCase {
  static final Duration FIRST_DELAY = Duration.ofSeconds(5);
  static final Duration MAX_DELAY = Duration.ofMinutes(5);
  static final Duration RETENTION = Duration.ofDays(30);
  static final int STUCK_ATTEMPTS = 20;

  private static final System.Logger LOG = System.getLogger(OutboxDeliveryService.class.getName());

  private final OutboxPort outbox;
  private final NotificationDeliveryPort delivery;
  private final UnitOfWorkPort unitOfWork;
  private final Clock clock;

  /**
   * Assemble le relais.
   *
   * @param outbox messages
   * @param delivery client de {@code POST /internal/notifications}
   * @param unitOfWork transaction courte de réservation du lot
   * @param clock horloge des échéances
   */
  public OutboxDeliveryService(
      OutboxPort outbox,
      NotificationDeliveryPort delivery,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    this.outbox = outbox;
    this.delivery = delivery;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  @Override
  public int deliverDue(int batchSize) {
    Instant now = clock.instant();
    List<PendingMessage> due = unitOfWork.inTransaction(() -> outbox.lockDue(now, batchSize));
    for (PendingMessage message : due) {
      deliver(message, clock.instant());
    }
    return due.size();
  }

  @Override
  public int purgeDelivered() {
    return unitOfWork.inTransaction(
        () -> outbox.purgeDeliveredBefore(clock.instant().minus(RETENTION)));
  }

  private void deliver(PendingMessage message, Instant now) {
    DeliveryOutcome outcome;
    try {
      outcome = delivery.deliver(message.payload());
    } catch (RuntimeException e) {
      outcome = DeliveryOutcome.RETRYABLE_FAILURE;
    }
    if (outcome == DeliveryOutcome.DELIVERED) {
      outbox.markDelivered(message.id(), now);
    } else if (outcome == DeliveryOutcome.PERMANENT_FAILURE) {
      outbox.markFailed(message.id(), outcome.name());
      LOG.log(
          System.Logger.Level.ERROR,
          "Message d''outbox {0} ({1}) refusé définitivement par Symfony : intervention manuelle",
          String.valueOf(message.id()),
          message.type());
    } else {
      outbox.reschedule(message.id(), now.plus(backoff(message.attempts())), outcome.name());
      int attempts = message.attempts() + 1;
      if (attempts > STUCK_ATTEMPTS) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Message d''outbox {0} toujours en attente après {1} tentatives",
            String.valueOf(message.id()),
            String.valueOf(attempts));
      }
    }
  }

  /**
   * Délai avant la prochaine tentative : 5 s × 2^tentatives précédentes, plafonné à 5 min.
   *
   * @param previousAttempts tentatives déjà faites
   * @return délai
   */
  static Duration backoff(int previousAttempts) {
    if (previousAttempts >= 6) {
      return MAX_DELAY;
    }
    return FIRST_DELAY.multipliedBy(1L << previousAttempts);
  }
}
