package com.taskora.identity.domain.model;

import com.taskora.identity.domain.service.PasswordHashing;
import java.time.LocalDateTime;

/** Agrégat utilisateur. */
public final class User {
  private User() {}

  public static User rehydrate(
      UserId id,
      Email email,
      PersonName name,
      HashedPassword password,
      AvatarColor avatarColor,
      Roles roles,
      LocalDateTime createdAt) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public UserId id() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public Email email() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public PersonName name() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public HashedPassword password() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public AvatarColor avatarColor() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public Roles roles() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public LocalDateTime createdAt() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** Valide tout dans l'ordre (A8, E15, E18, E21) et produit un nouvel état ; this reste inchangé. */
  public ProfileUpdateResult updateProfile(ProfileUpdate update, PasswordHashing hashing) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
