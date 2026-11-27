package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.util.Optional;

/** Lecture de {@code taskora.user} (colonnes autorisées seulement, jamais {@code password}). */
public interface UserDirectoryPort {

  /**
   * Compte par identifiant.
   *
   * @param id identifiant
   * @return compte, ou vide
   */
  Optional<UserSummary> findById(UserId id);

  /**
   * Compte par e-mail, comparé en SQL avec la collation de la colonne (casse et espaces finaux
   * ignorés).
   *
   * @param email e-mail tel que reçu
   * @return compte, ou vide
   */
  Optional<UserSummary> findByEmail(String email);
}
