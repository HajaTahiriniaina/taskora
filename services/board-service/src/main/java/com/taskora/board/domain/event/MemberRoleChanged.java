package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Rôle changé ({@code MemberRoleChanged}).
 *
 * @param eventId identifiant unique
 * @param occurredAt instant
 * @param boardId tableau
 * @param actorId auteur
 * @param userId membre
 * @param role nouveau rôle
 */
public record MemberRoleChanged(
    UUID eventId, Instant occurredAt, BoardId boardId, UserId actorId, UserId userId, Role role)
    implements DomainEvent {}
