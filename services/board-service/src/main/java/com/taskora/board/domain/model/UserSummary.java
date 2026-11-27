package com.taskora.board.domain.model;

/**
 * Projection lue dans {@code taskora.user} à chaque requête (B-ADR-2 point 4, SC-BOARD-027).
 *
 * @param id compte
 * @param name nom
 * @param email e-mail stocké
 * @param avatarColor couleur d'avatar
 */
public record UserSummary(UserId id, String name, String email, String avatarColor) {}
