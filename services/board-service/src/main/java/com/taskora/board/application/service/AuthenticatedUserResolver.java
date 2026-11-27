package com.taskora.board.application.service;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.board.application.port.out.TokenVerifierPort;
import com.taskora.board.application.port.out.UserDirectoryPort;

/** Résolution de l'appelant (B-ADR-2 point 3). */
public class AuthenticatedUserResolver implements ResolveAuthenticatedUserUseCase {
  private final TokenVerifierPort tokens;
  private final UserDirectoryPort users;

  /**
   * Crée le cas d'utilisation.
   *
   * @param tokens vérification des jetons
   * @param users lecture de {@code taskora.user}
   */
  public AuthenticatedUserResolver(TokenVerifierPort tokens, UserDirectoryPort users) {
    this.tokens = tokens;
    this.users = users;
  }

  /** {@inheritDoc} */
  @Override
  public AuthenticatedUser resolve(String token) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Ports utilisés (pour l'assemblage et le diagnostic).
   *
   * @return description sans donnée
   */
  @Override
  public String toString() {
    return "AuthenticatedUserResolver["
        + tokens.getClass().getSimpleName()
        + ", "
        + users.getClass().getSimpleName()
        + "]";
  }
}
