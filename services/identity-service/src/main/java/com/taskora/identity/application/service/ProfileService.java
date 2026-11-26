package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.GetProfileUseCase;
import com.taskora.identity.application.port.in.UpdateProfileUseCase;
import com.taskora.identity.application.port.in.UserView;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.event.UserProfileUpdated;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.TokenRejection;
import com.taskora.identity.domain.model.ProfileUpdate;
import com.taskora.identity.domain.model.ProfileUpdateResult;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Lecture et modification du profil (A8). */
public class ProfileService implements GetProfileUseCase, UpdateProfileUseCase {
  private final UserRepositoryPort users;
  private final PasswordHasherPort hasher;
  private final LegacyUserProjectionPort projection;
  private final OutboxPort outbox;
  private final UnitOfWorkPort unitOfWork;
  private final Clock clock;

  /**
   * Assemble les cas d'utilisation du profil.
   *
   * @param users stockage des comptes
   * @param hasher hachage du nouveau mot de passe
   * @param projection projection taskora.user
   * @param outbox événements UserProfileUpdated
   * @param unitOfWork transaction unique de la modification
   * @param clock horloge des événements
   */
  public ProfileService(
      UserRepositoryPort users,
      PasswordHasherPort hasher,
      LegacyUserProjectionPort projection,
      OutboxPort outbox,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    this.users = users;
    this.hasher = hasher;
    this.projection = projection;
    this.outbox = outbox;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  @Override
  public UserView get(UserId id) {
    return UserViews.of(load(id));
  }

  @Override
  public UserView update(UserId id, ProfileUpdate update) {
    return unitOfWork.inTransaction(() -> {
      User current = load(id);
      // Toute la validation a lieu ici ; une exception n'écrit rien (A8, SC-IDENT-019).
      ProfileUpdateResult result = current.updateProfile(update, hasher);
      if (!result.anyChange()) {
        return UserViews.of(current);
      }
      User next = result.user();
      users.update(next);
      projection.upsert(next);
      if (!result.changedFields().isEmpty()) {
        outbox.appendEvent(
            new UserProfileUpdated(
                UUID.randomUUID(),
                clock.instant(),
                next.id(),
                next.name().value(),
                next.avatarColor().value(),
                List.copyOf(result.changedFields())));
      }
      return UserViews.of(next);
    });
  }

  /** Compte disparu entre la validation du jeton et l'appel : même refus que le filtre. */
  private User load(UserId id) {
    return users
        .findById(id)
        .orElseThrow(() -> new TokenRejectedException(TokenRejection.ACCOUNT_NOT_FOUND));
  }
}
