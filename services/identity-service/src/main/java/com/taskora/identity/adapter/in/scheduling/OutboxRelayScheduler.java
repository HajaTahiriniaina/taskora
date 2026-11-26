package com.taskora.identity.adapter.in.scheduling;

import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Relais de l'outbox (A10) : toutes les 5 s, lots de 20, désactivable par
 * identity.outbox.relay.enabled (le bean n'est alors pas créé, voir la configuration).
 */
public class OutboxRelayScheduler {
  static final int BATCH_SIZE = 20;
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

  private final DeliverPendingMessagesUseCase relay;

  public OutboxRelayScheduler(DeliverPendingMessagesUseCase relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void relay() {
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
}
