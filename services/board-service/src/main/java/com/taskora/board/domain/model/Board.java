package com.taskora.board.domain.model;

import java.time.Instant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tableau ({@code taskora.board}).
 *
 * @param id identifiant
 * @param title titre
 * @param description description brute, ou {@code null}
 * @param background fond
 * @param showCardNumbers affichage des numéros de carte
 * @param ownerId propriétaire
 * @param createdAt date de création
 * @param deletedAt mise à la corbeille, ou {@code null}
 * @param deletedBy auteur de la mise à la corbeille, ou {@code null}
 */
public record Board(
    BoardId id,
    BoardTitle title,
    @Nullable String description,
    Background background,
    boolean showCardNumbers,
    UserId ownerId,
    Instant createdAt,
    @Nullable Instant deletedAt,
    @Nullable UserId deletedBy) {}
