package com.taskora.identity.application.port.in;

/** Connexion. */
public interface AuthenticateUseCase {
  /**
   * Vérifie l'e-mail (tel que saisi, collation de la base) et le mot de passe, puis émet un jeton.
   *
   * @param command identifiants non nuls (contrôlés par l'adapter web, E19)
   * @return jeton et profil
   * @throws com.taskora.identity.domain.exception.InvalidCredentialsException si le compte est
   *     inconnu ou le mot de passe faux (même erreur, SC-IDENT-008)
   */
  AuthResult authenticate(LoginCommand command);
}
