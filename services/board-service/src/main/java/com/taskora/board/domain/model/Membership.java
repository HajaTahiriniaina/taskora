package com.taskora.board.domain.model;

import java.time.Instant;

/**
 * Adhésion d'un compte à un tableau ({@code board_member}).
 *
 * @param boardId tableau
 * @param userId compte
 * @param role rôle
 * @param favorite favori propre à ce membre
 * @param joinedAt date d'adhésion
 */
public record Membership(
    BoardId boardId, UserId userId, Role role, boolean favorite, Instant joinedAt) {}
