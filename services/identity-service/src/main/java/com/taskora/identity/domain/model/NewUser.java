package com.taskora.identity.domain.model;

import java.time.LocalDateTime;

/** Compte à créer (sans identifiant). */
public record NewUser(
    Email email,
    PersonName name,
    HashedPassword password,
    AvatarColor avatarColor,
    Roles roles,
    LocalDateTime createdAt) {}
