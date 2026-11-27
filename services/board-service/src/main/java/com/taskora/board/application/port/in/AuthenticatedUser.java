package com.taskora.board.application.port.in;

import com.taskora.board.domain.model.UserId;

/**
 * Appelant authentifié, relu dans {@code taskora.user} (jamais pris dans le jeton).
 *
 * @param id compte
 * @param email e-mail stocké
 */
public record AuthenticatedUser(UserId id, String email) {}
