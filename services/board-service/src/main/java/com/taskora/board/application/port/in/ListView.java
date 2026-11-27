package com.taskora.board.application.port.in;

import java.time.OffsetDateTime;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Liste ouverte de la coquille (schéma {@code List}).
 *
 * @param id liste
 * @param title titre
 * @param position position
 * @param color couleur, ou {@code null}
 * @param archivedAt archivage, ou {@code null}
 * @param cardCount cartes ouvertes
 */
public record ListView(
    int id,
    String title,
    int position,
    @Nullable String color,
    @Nullable OffsetDateTime archivedAt,
    int cardCount) {}
