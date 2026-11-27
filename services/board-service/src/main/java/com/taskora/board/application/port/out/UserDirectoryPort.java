package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.util.Locale;
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

  /**
   * Compte par identifiant, avec l'égalité de son e-mail et du {@code username} d'un jeton, en une
   * seule lecture (B-ADR-2 point 3, B-ADR-8 point 4). Par défaut, la comparaison reproduit en Java
   * la collation {@code utf8mb4_unicode_ci} pour les adresses ASCII (casse et espaces finaux
   * ignorés) ; l'adapter JDBC la fait faire par MariaDB.
   *
   * @param id identifiant
   * @param username revendication {@code username} du jeton
   * @return compte et résultat de la comparaison, ou vide
   */
  default Optional<MatchedUser> findByIdMatching(UserId id, String username) {
    return findById(id)
        .map(u -> new MatchedUser(u, collationKey(u.email()).equals(collationKey(username))));
  }

  private static String collationKey(String email) {
    int end = email.length();
    while (end > 0 && email.charAt(end - 1) == ' ') {
      end--;
    }
    return email.substring(0, end).toLowerCase(Locale.ROOT);
  }

  /**
   * Compte trouvé par identifiant.
   *
   * @param user compte
   * @param emailMatches son e-mail est égal au {@code username} du jeton (collation)
   */
  record MatchedUser(UserSummary user, boolean emailMatches) {}
}
