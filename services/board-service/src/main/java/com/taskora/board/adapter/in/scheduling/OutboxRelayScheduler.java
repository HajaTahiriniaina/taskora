package com.taskora.board.adapter.in.scheduling;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Relais de l'outbox (B-ADR-5 point 2) : toutes les 5 s, lots de 50 ; purge quotidienne des
 * messages livrés depuis plus de 30 jours. Désactivable par {@code board.outbox.relay.enabled}
 * (aucun passage n'agit alors).
 */
public class OutboxRelayScheduler {
  static final int BATCH_SIZE = 50;
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

  private final DeliverPendingMessagesUseCase relay;
  private final boolean enabled;

  public OutboxRelayScheduler(DeliverPendingMessagesUseCase relay, boolean enabled) {
    this.relay = relay;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void relay() {
    if (!enabled) {
      return;
    }
    try {
      int processed;
      do {
        processed = relay.deliverDue(BATCH_SIZE);
      } while (processed == BATCH_SIZE);
    } catch (RuntimeException e) {
      // Base indisponible, etc. : nouvel essai au prochain passage, sans donnée dans le journal.
      LOG.warn("Relais de l'outbox en échec : {}", e.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelay = 86_400_000, initialDelay = 600_000)
  public void purge() {
    if (!enabled) {
      return;
    }
    try {
      relay.purgeDelivered();
    } catch (RuntimeException e) {
      LOG.warn("Purge de l'outbox en échec : {}", e.getClass().getSimpleName());
    }
  }
}
