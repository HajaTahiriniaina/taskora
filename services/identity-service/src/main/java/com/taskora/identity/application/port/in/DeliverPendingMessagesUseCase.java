package com.taskora.identity.application.port.in;

import java.util.UUID;

/** Livraison des messages d'outbox vers Symfony. */
public interface DeliverPendingMessagesUseCase {
  /**
   * Livre un lot de claims d'invitation dus, verrouillés (SKIP LOCKED) jusqu'à la fin du lot.
   *
   * @param batchSize nombre maximal de messages pris
   * @return nombre de messages traités (livrés, replanifiés ou en échec définitif)
   */
  int deliverDue(int batchSize);

  /**
   * Tente la livraison synchrone d'un claim juste après l'inscription (A10).
   *
   * @param eventId identifiant du message d'outbox
   * @return {@code true} si Symfony l'a accepté ; {@code false} s'il est replanifié, en échec ou
   *     déjà pris par le relais
   */
  boolean deliverNow(UUID eventId);
}
