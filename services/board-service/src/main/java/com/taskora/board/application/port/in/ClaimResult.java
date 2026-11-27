package com.taskora.board.application.port.in;

import java.util.List;

/**
 * Résultat de la consommation des invitations ({@code InvitationClaimResult}).
 *
 * @param claimed adhésions créées
 * @param boardIds tableaux rejoints
 */
public record ClaimResult(int claimed, List<Integer> boardIds) {}
