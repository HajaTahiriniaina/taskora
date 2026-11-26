package com.taskora.identity.domain.model;

import java.util.Set;

/** Nouvel état après modification, champs publics changés et indicateur de mot de passe. */
public record ProfileUpdateResult(User user, Set<String> changedFields, boolean passwordChanged) {}
