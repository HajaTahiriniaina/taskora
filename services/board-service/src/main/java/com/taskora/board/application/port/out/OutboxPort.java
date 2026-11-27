package com.taskora.board.application.port.out;

import com.taskora.board.domain.event.DomainEvent;
import com.taskora.board.domain.event.NotificationRequested;
import com.taskora.board.domain.model.BoardId;
import java.time.Instant;
import java.util.List;

/** Outbox transactionnelle {@code board.outbox_message} (B-ADR-5). */
public interface OutboxPort {

  /**
   * Écrit un événement de domaine ({@code broker}, {@code HELD}) dans la transaction courante.
   *
   * @param event événement
   */
  void append(DomainEvent event);

  /**
   * Écrit une notification ({@code symfony-notifications}, {@code PENDING}) dans la transaction
   * courante.
   *
   * @param notification notification composée, rattachée à un tableau
   * @throws IllegalArgumentException si la notification ne cite aucun tableau (utiliser {@link
   *     #append(NotificationRequested, BoardId)})
   */
  void append(NotificationRequested notification);

  /**
   * Écrit une notification rattachée à son tableau ({@code aggregate_id}) quand la charge n'en cite
   * pas ({@code member_removed}, FA13).
   *
   * @param notification notification composée
   * @param aggregate tableau concerné
   */
  void append(NotificationRequested notification, BoardId aggregate);

  /**
   * Verrouille un lot de messages dus ({@code FOR UPDATE SKIP LOCKED}).
   *
   * @param now instant courant (UTC)
   * @param limit taille du lot
   * @return messages
   */
  List<PendingMessage> lockDue(Instant now, int limit);

  /**
   * Message livré.
   *
   * @param id message
   * @param at instant
   */
  void markDelivered(long id, Instant at);

  /**
   * Échec définitif.
   *
   * @param id message
   * @param error cause sans donnée personnelle
   */
  void markFailed(long id, String error);

  /**
   * Échec rejouable : nouvelle échéance.
   *
   * @param id message
   * @param next prochaine tentative
   * @param error cause sans donnée personnelle
   */
  void reschedule(long id, Instant next, String error);

  /**
   * Purge des messages livrés avant un instant.
   *
   * @param before limite
   * @return nombre de messages supprimés
   */
  int purgeDeliveredBefore(Instant before);
}
