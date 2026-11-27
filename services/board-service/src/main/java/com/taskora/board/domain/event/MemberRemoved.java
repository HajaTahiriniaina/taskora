package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Membre retiré ou parti ({@code MemberRemoved}).
 *
 * @param eventId identifiant unique
 * @param occurredAt instant
 * @param boardId tableau
 * @param actorId auteur
 * @param userId membre retiré
 * @param self vrai si le membre est parti lui-même
 */
public record MemberRemoved(
    UUID eventId, Instant occurredAt, BoardId boardId, UserId actorId, UserId userId, boolean self)
    implements DomainEvent {}
