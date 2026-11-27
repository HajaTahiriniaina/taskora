package com.taskora.board.domain.model;

import java.time.Instant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tableau à créer (identifiant attribué par {@code AUTO_INCREMENT}).
 *
 * @param title titre
 * @param description description brute, ou {@code null}
 * @param background fond
 * @param ownerId propriétaire
 * @param createdAt date de création
 */
public record NewBoard(
    BoardTitle title,
    @Nullable String description,
    Background background,
    UserId ownerId,
    Instant createdAt) {}
