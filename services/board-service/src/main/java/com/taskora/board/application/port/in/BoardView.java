package com.taskora.board.application.port.in;

import java.util.List;

/**
 * Coquille complète d'un tableau (schéma {@code Board}).
 *
 * @param summary résumé (compteurs = sommes sur les listes ouvertes)
 * @param trelloFiles fichiers Trello dont le statut n'est pas {@code done}
 * @param lists listes ouvertes triées {@code position, id}
 * @param labels étiquettes triées par id
 * @param customFields champs triés {@code position, id}
 * @param invitations invitations en attente
 */
public record BoardView(
    BoardSummaryView summary,
    int trelloFiles,
    List<ListView> lists,
    List<LabelView> labels,
    List<CustomFieldView> customFields,
    List<InvitationView> invitations) {}
