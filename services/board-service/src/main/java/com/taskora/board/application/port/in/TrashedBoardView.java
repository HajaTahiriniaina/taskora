package com.taskora.board.application.port.in;

import java.time.OffsetDateTime;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tableau de la corbeille (schéma {@code TrashedBoard}).
 *
 * @param id tableau
 * @param title titre
 * @param background fond
 * @param deletedAt mise à la corbeille (heure de Paris)
 * @param deletedBy nom de l'auteur, ou {@code null}
 * @param purgeAt {@code deletedAt} + 30 jours calendaires (Paris)
 */
public record TrashedBoardView(
    int id,
    String title,
    String background,
    OffsetDateTime deletedAt,
    @Nullable String deletedBy,
    OffsetDateTime purgeAt) {}
