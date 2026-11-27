package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.Role;
import java.time.OffsetDateTime;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Résumé de tableau (schéma {@code BoardSummary}).
 *
 * @param id tableau
 * @param title titre
 * @param description description, ou {@code null}
 * @param background fond
 * @param showCardNumbers affichage des numéros
 * @param favorite favori de l'appelant
 * @param role rôle de l'appelant, ou {@code null}
 * @param members membres triés par id d'adhésion
 * @param listCount listes ouvertes
 * @param cardCount cartes ouvertes des listes ouvertes
 * @param createdAt création (heure de Paris)
 */
public record BoardSummaryView(
    int id,
    String title,
    @Nullable String description,
    String background,
    boolean showCardNumbers,
    boolean favorite,
    @Nullable Role role,
    List<MemberView> members,
    int listCount,
    int cardCount,
    OffsetDateTime createdAt) {}
