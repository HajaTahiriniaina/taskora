package com.taskora.identity.adapter.out.persistence;

import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Rôles stockés en JSON (« [] » comme Symfony). */
final class JsonRoles {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

  private JsonRoles() {}

  static String write(List<String> roles) {
    return JSON.writeValueAsString(roles);
  }

  static List<String> read(String json) {
    return JSON.readValue(json, LIST);
  }
}
