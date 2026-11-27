package com.taskora.board.application.port.in;

import java.time.OffsetDateTime;

/**
 * Invitation en attente (schéma {@code Invitation}).
 *
 * @param id invitation
 * @param email e-mail
 * @param createdAt création (heure de Paris)
 */
public record InvitationView(int id, String email, OffsetDateTime createdAt) {}
