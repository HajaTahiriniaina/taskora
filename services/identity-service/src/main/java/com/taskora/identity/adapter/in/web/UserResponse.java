package com.taskora.identity.adapter.in.web;

import com.taskora.identity.application.port.in.UserView;

/** {"id","name","email","avatarColor"} (Presenter::user). */
public record UserResponse(int id, String name, String email, String avatarColor) {
  public static UserResponse of(UserView view) {
    return new UserResponse(view.id(), view.name(), view.email(), view.avatarColor());
  }
}
