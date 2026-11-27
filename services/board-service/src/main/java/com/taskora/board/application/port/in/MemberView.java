package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.Role;
import java.time.OffsetDateTime;

/**
 * Membre exposé (schéma {@code Member}).
 *
 * @param id compte
 * @param name nom actuel
 * @param email e-mail actuel
 * @param avatarColor couleur actuelle
 * @param role rôle
 * @param joinedAt adhésion (heure de Paris)
 */
public record MemberView(
    int id, String name, String email, String avatarColor, Role role, OffsetDateTime joinedAt) {}
