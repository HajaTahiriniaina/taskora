package com.taskora.board.application.port.out;

import java.util.UUID;

/**
 * Message d'outbox dû, verrouillé pour livraison.
 *
 * @param id identifiant technique
 * @param eventId identifiant d'événement
 * @param type type
 * @param destination destination ({@code symfony-notifications}…)
 * @param payload charge JSON
 * @param attempts tentatives déjà faites
 */
public record PendingMessage(
    long id, UUID eventId, String type, String destination, String payload, int attempts) {}
