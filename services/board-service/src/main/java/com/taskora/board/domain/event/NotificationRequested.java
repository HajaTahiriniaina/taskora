package com.taskora.board.domain.event;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.util.List;
import java.util.UUID;

/**
 * Notification déjà composée, livrée par l'outbox à {@code POST /internal/notifications} (schéma
 * {@code NotificationRequested}, destination {@code symfony-notifications}).
 *
 * @param eventId identifiant unique (idempotence côté Symfony)
 * @param type type
 * @param actorId auteur, ou {@code null} (« Quelqu'un »)
 * @param recipientIds destinataires (1 à 500)
 * @param message texte exact (500 caractères au plus)
 * @param boardId tableau, ou {@code null} ({@code member_removed})
 */
public record NotificationRequested(
    UUID eventId,
    NotificationType type,
    UserId actorId,
    List<UserId> recipientIds,
    String message,
    BoardId boardId) {}
