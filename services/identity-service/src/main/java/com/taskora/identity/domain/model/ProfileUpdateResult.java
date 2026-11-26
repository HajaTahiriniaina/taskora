package com.taskora.identity.domain.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Nouvel état après modification, champs publics changés et indicateur de mot de passe. */
public record ProfileUpdateResult(User user, Set<String> changedFields, boolean passwordChanged) {
  /**
   * Copie défensive des champs changés, dans l'ordre name puis avatarColor.
   *
   * @param user nouvel état
   * @param changedFields champs publics réellement modifiés
   * @param passwordChanged {@code true} si le hash a changé
   */
  public ProfileUpdateResult {
    changedFields = Collections.unmodifiableSet(new LinkedHashSet<>(changedFields));
  }

  /** Vrai si un champ persistant (public ou mot de passe) a changé. */
  public boolean anyChange() {
    return passwordChanged || !changedFields.isEmpty();
  }
}
