package com.taskora.identity.domain.event;

import com.taskora.identity.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Nom ou couleur modifiés. */
public record UserProfileUpdated(
    UUID eventId,
    Instant occurredAt,
    UserId userId,
    String name,
    String avatarColor,
    List<String> changed)
    implements DomainEvent {}
