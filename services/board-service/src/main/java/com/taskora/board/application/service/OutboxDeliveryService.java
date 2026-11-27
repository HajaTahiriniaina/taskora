package com.taskora.board.application.service;

import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;

/** Relais de l'outbox (B-ADR-5 point 2). Squelette. */
public class OutboxDeliveryService implements DeliverPendingMessagesUseCase {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public OutboxDeliveryService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public int deliverDue(int batchSize) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public int purgeDelivered() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
