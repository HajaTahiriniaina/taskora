package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.out.InvitationClaimPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import java.time.Clock;
import java.util.UUID;

/** Relais de l'outbox vers Symfony (A10 : backoff 5 s, 10 s, 20 s… plafonné à 5 min). */
public class OutboxDeliveryService implements DeliverPendingMessagesUseCase {

  public OutboxDeliveryService(
      OutboxPort outbox, InvitationClaimPort client, UnitOfWorkPort unitOfWork, Clock clock) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public int deliverDue(int batchSize) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public boolean deliverNow(UUID eventId) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
