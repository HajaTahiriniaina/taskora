package com.taskora.board.application.port.in;

/** Relais de l'outbox (B-ADR-5 point 2). */
public interface DeliverPendingMessagesUseCase {

  /**
   * Livre un lot de messages dus ({@code FOR UPDATE SKIP LOCKED}) : 200 → livré ; 400, 401, 409 →
   * échec définitif ; 404, 5xx, délai → rejouable avec backoff 5 s → 5 min.
   *
   * @param batchSize taille du lot
   * @return nombre de messages traités
   */
  int deliverDue(int batchSize);

  /**
   * Purge des messages livrés depuis plus de 30 jours.
   *
   * @return nombre de messages supprimés
   */
  int purgeDelivered();
}
