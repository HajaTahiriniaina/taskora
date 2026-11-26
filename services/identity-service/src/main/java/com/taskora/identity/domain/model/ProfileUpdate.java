package com.taskora.identity.domain.model;

import java.util.Optional;

/**
 * Modification de profil demandée. name présent = clé présente et non null ; newPassword présent =
 * ni absent, ni null, ni "" (E21 : "0" est un vrai mot de passe), calculé par l'adapter web.
 */
public record ProfileUpdate(
    Optional<String> name,
    Optional<String> avatarColor,
    Optional<String> currentPassword,
    Optional<String> newPassword) {}
