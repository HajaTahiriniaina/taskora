package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Tableau créé (schéma {@code BoardCreated} de board-service.internal.yaml).
 *
 * @param eventId identifiant unique
 * @param occurredAt instant
 * @param boardId tableau
 * @param actorId auteur
 * @param ownerId propriétaire
 */
public record BoardCreated(
    UUID eventId, Instant occurredAt, BoardId boardId, UserId actorId, UserId ownerId)
    implements DomainEvent {}
