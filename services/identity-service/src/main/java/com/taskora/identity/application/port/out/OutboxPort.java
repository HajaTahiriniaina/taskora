package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.event.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbox transactionnelle (A11). */
public interface OutboxPort {
  /** Statut HELD, destination broker. */
  void appendEvent(DomainEvent event);

  /**
   * Ajoute un claim d'invitation (destination symfony-invitations, statut PENDING).
   *
   * @param claim charge InvitationClaimRequest
   * @param availableAt première échéance de livraison
   */
  void appendInvitationClaim(InvitationClaim claim, Instant availableAt);

  /**
   * Verrouille (FOR UPDATE SKIP LOCKED) les claims dus, dans l'ordre d'insertion.
   *
   * @param now instant de référence
   * @param limit taille maximale du lot
   * @return claims verrouillés jusqu'à la fin de la transaction
   */
  List<PendingClaim> lockDueClaims(Instant now, int limit);

  /**
   * Verrouille un claim encore en attente, sans attendre s'il est déjà pris.
   *
   * @param eventId identifiant du message
   * @return le claim, ou vide s'il est livré, en échec ou verrouillé par le relais
   */
  Optional<PendingClaim> lockClaim(UUID eventId);

  /**
   * Marque le message livré.
   *
   * @param eventId identifiant du message
   * @param at instant de livraison
   */
  void markDelivered(UUID eventId, Instant at);

  /**
   * Replanifie le message après un échec rejouable.
   *
   * @param eventId identifiant du message
   * @param attempts nombre total de tentatives
   * @param nextAttemptAt prochaine échéance (backoff)
   * @param errorCode cause sans donnée personnelle
   */
  void markRetry(UUID eventId, int attempts, Instant nextAttemptAt, String errorCode);

  /**
   * Marque le message en échec définitif (intervention manuelle).
   *
   * @param eventId identifiant du message
   * @param errorCode cause sans donnée personnelle
   */
  void markFailed(UUID eventId, String errorCode);
}
