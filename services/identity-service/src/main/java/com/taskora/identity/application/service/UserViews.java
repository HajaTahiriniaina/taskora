package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.UserView;
import com.taskora.identity.domain.model.User;

/** Représentation publique d'un utilisateur (Presenter::user de Symfony). */
final class UserViews {
  private UserViews() {}

  static UserView of(User user) {
    return new UserView(
        user.id().value(), user.name().value(), user.email().value(), user.avatarColor().value());
  }
}
