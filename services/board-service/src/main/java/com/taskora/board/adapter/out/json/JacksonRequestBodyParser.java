package com.taskora.board.adapter.out.json;

import com.taskora.board.application.port.out.ParsedBody;
import com.taskora.board.application.port.out.RequestBodyParser;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Analyse du corps après la politique d'accès (B-ADR-3 point 3, U13) : corps vide → {@code {}} ;
 * corps non JSON ou JSON valide qui n'est pas un objet → 400 « JSON invalide. ».
 */
public class JacksonRequestBodyParser implements RequestBodyParser {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public ParsedBody parse(byte[] raw) {
    if (raw.length == 0) {
      return new ParsedBody(Map.of());
    }
    JsonNode root;
    try {
      root = JSON.readTree(raw);
    } catch (JacksonException e) {
      throw invalid();
    }
    if (root == null || !root.isObject()) {
      throw invalid();
    }
    return new ParsedBody(object(root));
  }

  private static ValidationException invalid() {
    return new ValidationException(DomainMessages.INVALID_JSON);
  }

  private static Map<String, @Nullable Object> object(JsonNode node) {
    Map<String, @Nullable Object> map = new LinkedHashMap<>();
    node.properties().forEach(e -> map.put(e.getKey(), value(e.getValue())));
    return map;
  }

  /** Valeur Java d'un nœud : chaîne, booléen, nombre, liste, table ordonnée ou {@code null}. */
  private static @Nullable Object value(JsonNode node) {
    if (node.isObject()) {
      return object(node);
    }
    if (node.isArray()) {
      List<@Nullable Object> list = new ArrayList<>();
      node.values().forEach(v -> list.add(value(v)));
      return list;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToInt() ? (Object) node.intValue() : (Object) node.bigIntegerValue();
    }
    if (node.isNumber()) {
      return node.doubleValue();
    }
    return null;
  }
}
