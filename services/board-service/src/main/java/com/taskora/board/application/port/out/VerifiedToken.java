package com.taskora.board.application.port.out;

/**
 * Revendications utiles d'un jeton dont la signature et les dates ont été vérifiées.
 *
 * @param uid revendication {@code uid} (jetons d'identity), ou {@code null}
 * @param username revendication {@code username} (= e-mail), ou {@code null}
 */
public record VerifiedToken(Integer uid, String username) {}
