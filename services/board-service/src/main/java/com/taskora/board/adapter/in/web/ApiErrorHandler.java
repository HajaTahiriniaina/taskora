package com.taskora.board.adapter.in.web;

import com.taskora.board.domain.exception.BoardDomainException;
import com.taskora.board.domain.exception.BoardInTrashException;
import com.taskora.board.domain.exception.BoardNotFoundException;
import com.taskora.board.domain.exception.ConflictException;
import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.NotInTrashException;
import com.taskora.board.domain.exception.ResourceNotFoundException;
import com.taskora.board.domain.exception.TokenRejectedException;
import com.taskora.board.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Format unique des erreurs (A4) : {@code {"error": <message exact du contrat>}} ; 5xx sans trace
 * ni message d'exception (qui pourrait citer une donnée). Les réponses d'un non-membre et d'une
 * ressource inexistante passent par le même chemin (mêmes en-têtes, SC-BOARD-021).
 */
@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

  @ExceptionHandler({
    BoardNotFoundException.class,
    BoardInTrashException.class,
    NotInTrashException.class,
    ResourceNotFoundException.class
  })
  public ResponseEntity<Map<String, String>> notFound(BoardDomainException e) {
    return error(HttpStatus.NOT_FOUND, message(e));
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
    return error(HttpStatus.FORBIDDEN, message(e));
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<Map<String, String>> validation(ValidationException e) {
    return error(HttpStatus.BAD_REQUEST, message(e));
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
    return error(HttpStatus.CONFLICT, message(e));
  }

  /** Compte disparu pendant la requête : même réponse que le filtre JWT. */
  @ExceptionHandler(TokenRejectedException.class)
  public ResponseEntity<Map<String, Object>> tokenRejected(TokenRejectedException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", HttpStatus.UNAUTHORIZED.value());
    body.put("message", e.rejection().message());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, String>> noRoute() {
    return error(HttpStatus.NOT_FOUND, ErrorMessages.RESOURCE_NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, String>> methodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    Set<HttpMethod> supported = e.getSupportedHttpMethods();
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .allow(supported == null ? new HttpMethod[0] : supported.toArray(new HttpMethod[0]))
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", "Méthode non autorisée."));
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

  private static String message(BoardDomainException e) {
    String message = e.getMessage();
    return message == null ? ErrorMessages.INTERNAL_ERROR : message;
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", message));
  }
}
