package com.taskora.identity.domain.service;

import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;

/** Hachage des mots de passe (interface de domaine). */
public interface PasswordHashing {
  /**
   * Hache un nouveau mot de passe ($2y$, coût configuré).
   *
   * @param password mot de passe contrôlé (8 points de code à 4096 octets)
   * @return hash
   */
  HashedPassword hash(PlainPassword password);

  /**
   * Vérifie un mot de passe contre un hash, sans jamais lever d'exception.
   *
   * @param password mot de passe saisi
   * @param hash hash stocké
   * @return {@code true} s'ils correspondent ; {@code false} pour un hash corrompu ou inconnu
   */
  boolean matches(PlainPassword password, HashedPassword hash);
}
