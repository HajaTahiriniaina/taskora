package com.taskora.identity.adapter.in.web;

import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.exception.InvalidCredentialsException;
import com.taskora.identity.domain.exception.InvalidCurrentPasswordException;
import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Format unique des erreurs (A4) : {"error"} ou, pour les 401, {"code":401,"message"}. */
@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<Map<String, String>> validation(ValidationException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(PasswordTooLongException.class)
  public ResponseEntity<Map<String, String>> passwordTooLong() {
    return error(HttpStatus.BAD_REQUEST, ErrorMessages.PASSWORD_TOO_LONG);
  }

  @ExceptionHandler(InvalidCurrentPasswordException.class)
  public ResponseEntity<Map<String, String>> wrongCurrentPassword() {
    return error(HttpStatus.BAD_REQUEST, ErrorMessages.WRONG_CURRENT_PASSWORD);
  }

  @ExceptionHandler(EmailAlreadyUsedException.class)
  public ResponseEntity<Map<String, String>> emailAlreadyUsed() {
    return error(HttpStatus.CONFLICT, ErrorMessages.EMAIL_ALREADY_USED);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Map<String, Object>> invalidCredentials() {
    return unauthorized(ErrorMessages.INVALID_CREDENTIALS);
  }

  /** Compte disparu pendant la requête (même réponse que le filtre JWT). */
  @ExceptionHandler(TokenRejectedException.class)
  public ResponseEntity<Map<String, Object>> tokenRejected() {
    return unauthorized(ErrorMessages.INVALID_CREDENTIALS);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, String>> methodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    HttpHeaders headers = new HttpHeaders();
    if (e.getSupportedHttpMethods() != null) {
      headers.setAllow(e.getSupportedHttpMethods());
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", ErrorMessages.METHOD_NOT_ALLOWED));
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, String>> notFound() {
    return error(HttpStatus.NOT_FOUND, ErrorMessages.NOT_FOUND);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<Map<String, String>> notAcceptable() {
    return error(HttpStatus.NOT_ACCEPTABLE, "Format de réponse non disponible.");
  }

  /** 5xx : jamais de trace ni de message d'exception (qui pourrait contenir des données). */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> internal(Exception e) {
    RuntimeException sanitized = new RuntimeException(e.getClass().getName());
    sanitized.setStackTrace(e.getStackTrace());
    LOG.error("Erreur interne non gérée", sanitized);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.INTERNAL_ERROR);
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", message));
  }

  private static ResponseEntity<Map<String, Object>> unauthorized(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", HttpStatus.UNAUTHORIZED.value());
    body.put("message", message);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }
}
