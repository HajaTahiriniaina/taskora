package com.taskora.identity.adapter.in.web;

import com.taskora.identity.application.port.in.AuthenticateUseCase;
import com.taskora.identity.application.port.in.RegisterUserCommand;
import com.taskora.identity.application.port.in.RegisterUserUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/auth/register et POST /api/auth/login (publics). */
@RestController
public class AuthController {
  private final RegisterUserUseCase register;
  private final AuthenticateUseCase authenticate;

  public AuthController(RegisterUserUseCase register, AuthenticateUseCase authenticate) {
    this.register = register;
    this.authenticate = authenticate;
  }

  /** Forme (E16, E20) puis type de name, email, password (E17), avant tout contrôle de contenu. */
  @PostMapping("/api/auth/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(HttpServletRequest request) {
    PhpJsonBody body = PhpJsonBody.parse(RequestBodies.read(request));
    String name = body.requiredString("name");
    String email = body.requiredString("email");
    String password = body.requiredString("password");
    return AuthResponse.of(register.register(new RegisterUserCommand(name, email, password)));
  }

  /** Règles françaises d'E19 ; Content-Type non contrôlé ; identifiants refusés → 401 Lexik. */
  @PostMapping("/api/auth/login")
  public AuthResponse login(HttpServletRequest request) {
    return AuthResponse.of(
        authenticate.authenticate(JsonLoginRequestParser.parse(RequestBodies.read(request))));
  }
}
