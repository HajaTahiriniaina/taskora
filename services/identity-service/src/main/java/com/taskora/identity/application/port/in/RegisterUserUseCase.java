package com.taskora.identity.application.port.in;

/** Inscription. */
public interface RegisterUserUseCase {
  /**
   * Crée un compte (contrôles dans l'ordre de identity.md §2), le projette dans taskora.user et
   * prépare la consommation des invitations, puis émet un jeton.
   *
   * @param command champs de l'inscription ({@code ""} pour une clé absente)
   * @return jeton et profil
   * @throws com.taskora.identity.domain.exception.ValidationException champ invalide (400)
   * @throws com.taskora.identity.domain.exception.PasswordTooLongException plus de 4096 octets
   * @throws com.taskora.identity.domain.exception.EmailAlreadyUsedException e-mail déjà pris (409)
   */
  AuthResult register(RegisterUserCommand command);
}
