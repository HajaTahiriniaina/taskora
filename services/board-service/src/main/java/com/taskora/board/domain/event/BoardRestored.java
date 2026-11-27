package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Tableau restauré ({@code BoardRestored}).
 *
 * @param eventId identifiant unique
 * @param occurredAt instant
 * @param boardId tableau
 * @param actorId auteur
 */
public record BoardRestored(UUID eventId, Instant occurredAt, BoardId boardId, UserId actorId)
    implements DomainEvent {}
