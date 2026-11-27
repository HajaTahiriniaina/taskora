package com.taskora.board.domain.model;

import java.time.Instant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Invitation en attente d'un e-mail sans compte ({@code invitation}).
 *
 * @param id identifiant
 * @param boardId tableau
 * @param email e-mail normalisé
 * @param invitedBy inviteur, ou {@code null} (compte supprimé)
 * @param createdAt date de création
 */
public record Invitation(
    int id, BoardId boardId, String email, @Nullable UserId invitedBy, Instant createdAt) {}
