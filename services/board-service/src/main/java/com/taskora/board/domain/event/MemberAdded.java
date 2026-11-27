package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Membre ajouté directement ou par consommation d'invitation ({@code MemberAdded}).
 *
 * @param eventId identifiant unique
 * @param occurredAt instant
 * @param boardId tableau
 * @param actorId auteur
 * @param userId nouveau membre
 * @param role rôle ({@code admin} ou {@code member})
 * @param viaInvitation vrai si l'adhésion vient d'une invitation
 */
public record MemberAdded(
    UUID eventId,
    Instant occurredAt,
    BoardId boardId,
    @Nullable UserId actorId,
    UserId userId,
    Role role,
    boolean viaInvitation)
    implements DomainEvent {}
