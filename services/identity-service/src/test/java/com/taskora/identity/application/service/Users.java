package com.taskora.identity.application.service;

import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PersonName;
import com.taskora.identity.domain.model.Roles;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.time.LocalDateTime;
import java.util.List;

/** Comptes de test. */
final class Users {
  static final String HASH = "$2y$04$abcdefghijklmnopqrstuuWj1z8m5eT8Yb8eF6l2hYxE6m8dGJQ2K";

  private Users() {}

  static User camille() {
    return User.rehydrate(
        new UserId(4),
        Email.ofStored("camille@exemple.fr"),
        PersonName.of("Camille"),
        new HashedPassword(HASH),
        AvatarColor.parse("#14b8a6"),
        new Roles(List.of()),
        LocalDateTime.of(2026, 11, 26, 10, 0));
  }
}
