package com.taskora.identity.adapter.out.persistence;

import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.NewUser;
import com.taskora.identity.domain.model.PersonName;
import com.taskora.identity.domain.model.Roles;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

/** Adapter JPA de UserRepositoryPort. */
public class JpaUserRepositoryAdapter implements UserRepositoryPort {
  private final UserAccountJpaRepository repository;
  private final Clock clock;

  public JpaUserRepositoryAdapter(UserAccountJpaRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public Optional<User> findByLoginEmail(String rawEmail) {
    return repository.findByEmail(rawEmail).map(JpaUserRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<User> findByEmail(Email email) {
    return repository.findByEmail(email.value()).map(JpaUserRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<User> findById(UserId id) {
    return repository.findById(id.value()).map(JpaUserRepositoryAdapter::toDomain);
  }

  @Override
  public boolean existsByEmail(Email email) {
    return repository.existsByEmail(email.value());
  }

  /** Violation de uq_user_account_email (seule contrainte unique hors clé) → 409 (A7). */
  @Override
  public User insert(NewUser user) {
    UserAccountEntity entity =
        new UserAccountEntity(
            user.email().value(),
            user.name().value(),
            user.password().value(),
            user.avatarColor().value(),
            JsonRoles.write(user.roles().stored()),
            user.createdAt());
    try {
      return toDomain(repository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException e) {
      throw new EmailAlreadyUsedException();
    }
  }

  @Override
  public void update(User user) {
    UserAccountEntity entity =
        repository
            .findById(user.id().value())
            .orElseThrow(() -> new IllegalStateException("Compte " + user.id().value() + " absent"));
    entity.applyProfile(
        user.name().value(),
        user.password().value(),
        user.avatarColor().value(),
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    repository.saveAndFlush(entity);
  }

  private static User toDomain(UserAccountEntity e) {
    return User.rehydrate(
        new UserId(e.getId()),
        Email.ofStored(e.getEmail()),
        new PersonName(e.getName()),
        new HashedPassword(e.getPasswordHash()),
        new AvatarColor(e.getAvatarColor()),
        new Roles(JsonRoles.read(e.getRoles())),
        e.getCreatedAt());
  }
}
