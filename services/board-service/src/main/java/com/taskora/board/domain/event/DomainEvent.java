package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/** Événement de domaine écrit dans l'outbox (destination {@code broker}, statut {@code HELD}). */
public interface DomainEvent {

  /**
   * Identifiant unique (UUID v4).
   *
   * @return identifiant
   */
  UUID eventId();

  /**
   * Instant de l'événement (UTC dans la charge).
   *
   * @return instant
   */
  Instant occurredAt();

  /**
   * Tableau concerné.
   *
   * @return tableau
   */
  BoardId boardId();

  /**
   * Auteur, ou {@code null}.
   *
   * @return auteur
   */
  UserId actorId();
}
