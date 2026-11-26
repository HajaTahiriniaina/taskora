package com.taskora.identity.application.port.in;

import java.util.UUID;

/** Livraison des messages d'outbox vers Symfony. */
public interface DeliverPendingMessagesUseCase {
  int deliverDue(int batchSize);

  boolean deliverNow(UUID eventId);
}
