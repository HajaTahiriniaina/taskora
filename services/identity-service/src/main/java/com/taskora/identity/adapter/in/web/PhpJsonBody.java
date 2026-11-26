package com.taskora.identity.adapter.in.web;

import com.taskora.identity.domain.exception.ValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lecture stricte du corps JSON (nom conservé ; aucune conversion PHP depuis E16, E17, E20, E21).
 */
public final class PhpJsonBody {
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private final JsonNode object;

  private PhpJsonBody(JsonNode object) {
    this.object = object;
  }

  /**
   * Corps vide, non JSON ou JSON qui n'est pas un objet → ValidationException("JSON invalide.")
   * (E16, E20).
   */
  public static PhpJsonBody parse(byte[] body) {
    if (body == null || isBlank(body)) {
      throw new ValidationException(ErrorMessages.INVALID_JSON);
    }
    JsonNode node;
    try {
      node = MAPPER.readTree(body);
    } catch (JacksonException e) {
      // Le message de Jackson (extrait du corps) n'est jamais propagé.
      throw new ValidationException(ErrorMessages.INVALID_JSON);
    }
    if (node == null || !node.isObject()) {
      throw new ValidationException(ErrorMessages.INVALID_JSON);
    }
    return new PhpJsonBody(node);
  }

  public boolean has(String key) {
    return object.has(key);
  }

  /** Présent et non null. */
  public boolean isSetLikePhp(String key) {
    JsonNode value = object.get(key);
    return value != null && !value.isNull();
  }

  /**
   * Inscription et connexion : null si la clé est absente ; présente et non chaîne (null compris) →
   * ValidationException("Le champ \"key\" doit être une chaîne de caractères.") (E17, E19).
   */
  public String requiredString(String key) {
    JsonNode value = object.get(key);
    if (value == null) {
      return null;
    }
    return asString(key, value);
  }

  /** PATCH /api/me : null si absente ou null ; autre non-chaîne → même exception (E17). */
  public String optionalString(String key) {
    JsonNode value = object.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    return asString(key, value);
  }

  private static String asString(String key, JsonNode value) {
    if (!value.isString()) {
      throw new ValidationException(ErrorMessages.notAString(key));
    }
    return value.stringValue();
  }

  /** Corps vide ou fait uniquement d'espaces JSON. */
  private static boolean isBlank(byte[] body) {
    for (byte b : body) {
      if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
        return false;
      }
    }
    return true;
  }
}
