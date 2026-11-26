package com.taskora.identity.adapter.in.web;

import com.taskora.identity.application.port.in.AuthResult;

/** {"token","user"}. */
public record AuthResponse(String token, UserResponse user) {
  public static AuthResponse of(AuthResult result) {
    return new AuthResponse(result.token(), UserResponse.of(result.user()));
  }
}
