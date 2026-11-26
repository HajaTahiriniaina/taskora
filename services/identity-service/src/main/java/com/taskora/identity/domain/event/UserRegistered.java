package com.taskora.identity.domain.event;

import com.taskora.identity.domain.model.UserId;
import java.time.Instant;
import java.util.UUID;

/** Compte créé. */
public record UserRegistered(
    UUID eventId, Instant occurredAt, UserId userId, String email, String name, String avatarColor)
    implements DomainEvent {}
