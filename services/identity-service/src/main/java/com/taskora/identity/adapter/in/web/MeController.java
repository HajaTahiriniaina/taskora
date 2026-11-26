package com.taskora.identity.adapter.in.web;

import com.taskora.identity.application.port.in.AuthenticatedUser;
import com.taskora.identity.application.port.in.GetProfileUseCase;
import com.taskora.identity.application.port.in.UpdateProfileUseCase;
import com.taskora.identity.domain.model.ProfileUpdate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET et PATCH /api/me (jeton obligatoire ; aucun identifiant dans la route : pas d'IDOR). */
@RestController
public class MeController {
  private final GetProfileUseCase profile;
  private final UpdateProfileUseCase update;

  public MeController(GetProfileUseCase profile, UpdateProfileUseCase update) {
    this.profile = profile;
    this.update = update;
  }

  @GetMapping("/api/me")
  public UserResponse get(@AuthenticationPrincipal AuthenticatedUser me) {
    return UserResponse.of(profile.get(me.id()));
  }

  /**
   * Forme (E16, E20) puis type de name, avatarColor, currentPassword, newPassword (E17, null =
   * absent) ; newPassword absent, null ou "" = pas de changement (E21).
   */
  @PatchMapping("/api/me")
  public UserResponse patch(
      @AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
    PhpJsonBody body = PhpJsonBody.parse(RequestBodies.read(request));
    String name = body.optionalString("name");
    String avatarColor = body.optionalString("avatarColor");
    String currentPassword = body.optionalString("currentPassword");
    String newPassword = body.optionalString("newPassword");
    ProfileUpdate change =
        new ProfileUpdate(
            Optional.ofNullable(name),
            Optional.ofNullable(avatarColor),
            Optional.ofNullable(currentPassword),
            Optional.ofNullable(newPassword).filter(value -> !value.isEmpty()));
    return UserResponse.of(update.update(me.id(), change));
  }
}
