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

  Optional<User> findByEmail(Email email);

  Optional<User> findById(UserId id);

  boolean existsByEmail(Email email);

  /** Lève EmailAlreadyUsedException sur violation d'unicité. */
  User insert(NewUser user);

  void update(User user);
}
