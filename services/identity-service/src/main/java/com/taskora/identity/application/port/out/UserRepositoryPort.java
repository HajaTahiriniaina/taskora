package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.NewUser;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.util.Optional;

/** Stockage des comptes. */
public interface UserRepositoryPort {
  /** Recherche par e-mail tel que saisi (collation de la colonne, A7). */
  Optional<User> findByLoginEmail(String rawEmail);

  /**
   * Recherche par e-mail normalisé.
   *
   * @param email e-mail de l'inscription
   * @return le compte, s'il existe
   */
  Optional<User> findByEmail(Email email);

  /**
   * Recherche par identifiant historique.
   *
   * @param id identifiant
   * @return le compte, s'il existe
   */
  Optional<User> findById(UserId id);

  /**
   * Indique si l'e-mail est déjà pris (collation : casse, accents, espaces finaux).
   *
   * @param email e-mail normalisé
   * @return {@code true} si un compte l'utilise
   */
  boolean existsByEmail(Email email);

  /** Lève EmailAlreadyUsedException sur violation d'unicité. */
  User insert(NewUser user);

  /**
   * Enregistre le nouvel état (nom, couleur, hash) ; l'e-mail n'est jamais modifié.
   *
   * @param user état à persister
   */
  void update(User user);
}
