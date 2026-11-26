package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.InvalidCurrentPasswordException;
import com.taskora.identity.domain.service.PasswordHashing;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Agrégat utilisateur. */
public final class User {
  public static final String FIELD_NAME = "name";
  public static final String FIELD_AVATAR_COLOR = "avatarColor";

  private final UserId id;
  private final Email email;
  private final PersonName name;
  private final HashedPassword password;
  private final AvatarColor avatarColor;
  private final Roles roles;
  private final LocalDateTime createdAt;

  private User(
      UserId id,
      Email email,
      PersonName name,
      HashedPassword password,
      AvatarColor avatarColor,
      Roles roles,
      LocalDateTime createdAt) {
    this.id = Objects.requireNonNull(id);
    this.email = Objects.requireNonNull(email);
    this.name = Objects.requireNonNull(name);
    this.password = Objects.requireNonNull(password);
    this.avatarColor = Objects.requireNonNull(avatarColor);
    this.roles = Objects.requireNonNull(roles);
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  /**
   * Reconstruit un compte lu en base ou tout juste inséré.
   *
   * @param id identifiant historique
   * @param email e-mail normalisé
   * @param name nom
   * @param password hash
   * @param avatarColor couleur
   * @param roles rôles stockés
   * @param createdAt date d'inscription (heure de Paris)
   * @return compte
   */
  public static User rehydrate(
      UserId id,
      Email email,
      PersonName name,
      HashedPassword password,
      AvatarColor avatarColor,
      Roles roles,
      LocalDateTime createdAt) {
    return new User(id, email, name, password, avatarColor, roles, createdAt);
  }

  /**
   * Identifiant historique, partagé avec taskora.user (A3).
   *
   * @return identifiant
   */
  public UserId id() {
    return id;
  }

  /**
   * E-mail normalisé, identifiant de connexion ; jamais modifié (SC-IDENT-020).
   *
   * @return e-mail
   */
  public Email email() {
    return email;
  }

  /**
   * Nom affiché.
   *
   * @return nom
   */
  public PersonName name() {
    return name;
  }

  /**
   * Hash du mot de passe (jamais exposé).
   *
   * @return hash
   */
  public HashedPassword password() {
    return password;
  }

  /**
   * Couleur d'avatar.
   *
   * @return couleur
   */
  public AvatarColor avatarColor() {
    return avatarColor;
  }

  /**
   * Rôles stockés.
   *
   * @return rôles
   */
  public Roles roles() {
    return roles;
  }

  /**
   * Date d'inscription en heure locale Europe/Paris, comme Symfony.
   *
   * @return date d'inscription
   */
  public LocalDateTime createdAt() {
    return createdAt;
  }

  /**
   * Valide tout dans l'ordre (A8, E15, E18, E21) et produit un nouvel état ; this reste inchangé.
   */
  public ProfileUpdateResult updateProfile(ProfileUpdate update, PasswordHashing hashing) {
    PersonName newName = update.name().map(PersonName::of).orElse(name);
    AvatarColor newColor = update.avatarColor().map(AvatarColor::parse).orElse(avatarColor);
    HashedPassword newPassword = password;
    boolean passwordChanged = false;
    if (update.newPassword().isPresent()) {
      PlainPassword current = PlainPassword.of(update.currentPassword().orElse(""));
      if (current.isEmpty() || !hashing.matches(current, password)) {
        throw new InvalidCurrentPasswordException();
      }
      PlainPassword candidate = PlainPassword.of(update.newPassword().orElseThrow());
      candidate.requireMinimumLength();
      candidate.requireMaximumLength();
      newPassword = hashing.hash(candidate);
      passwordChanged = true;
    }
    Set<String> changed = new LinkedHashSet<>();
    if (!newName.equals(name)) {
      changed.add(FIELD_NAME);
    }
    if (!newColor.equals(avatarColor)) {
      changed.add(FIELD_AVATAR_COLOR);
    }
    User next = new User(id, email, newName, newPassword, newColor, roles, createdAt);
    return new ProfileUpdateResult(next, changed, passwordChanged);
  }
}
