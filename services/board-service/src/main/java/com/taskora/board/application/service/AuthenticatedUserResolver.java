package com.taskora.board.application.service;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.board.application.port.out.TokenVerifierPort;
import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.application.port.out.UserDirectoryPort.MatchedUser;
import com.taskora.board.application.port.out.VerifiedToken;
import com.taskora.board.domain.exception.TokenRejectedException;
import com.taskora.board.domain.exception.TokenRejection;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.util.function.Supplier;

/** Résolution de l'appelant (B-ADR-2 point 3). */
public class AuthenticatedUserResolver implements ResolveAuthenticatedUserUseCase {
  private static final Supplier<TokenRejectedException> UNKNOWN_ACCOUNT =
      () -> new TokenRejectedException(TokenRejection.UNKNOWN_ACCOUNT);

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
    VerifiedToken verified = tokens.verify(token);
    Integer uid = verified.uid();
    String username = verified.username();
    UserSummary user;
    if (uid != null && username != null) {
      MatchedUser matched =
          users.findByIdMatching(new UserId(uid), username).orElseThrow(UNKNOWN_ACCOUNT);
      if (!matched.emailMatches()) {
        throw new TokenRejectedException(TokenRejection.INVALID);
      }
      user = matched.user();
    } else if (uid != null) {
      user = users.findById(new UserId(uid)).orElseThrow(UNKNOWN_ACCOUNT);
    } else if (username != null) {
      user = users.findByEmail(username).orElseThrow(UNKNOWN_ACCOUNT);
    } else {
      throw new TokenRejectedException(TokenRejection.NO_USERNAME);
    }
    return new AuthenticatedUser(user.id(), user.email());
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
