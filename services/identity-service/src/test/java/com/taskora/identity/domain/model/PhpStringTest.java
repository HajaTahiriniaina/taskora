package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden : compat/php-string-functions.json (trim, mb_strlen, mb_substr(0,120), mb_strtolower de
 * PHP 8.4).
 */
class PhpStringTest {

  static Stream<Arguments> golden() {
    try (InputStream in =
        PhpStringTest.class.getResourceAsStream("/compat/php-string-functions.json")) {
      List<Arguments> out = new ArrayList<>();
      for (JsonNode n : JsonMapper.builder().build().readTree(in)) {
        out.add(
            Arguments.of(
                n.get("input").asString(),
                n.get("trim").asString(),
                n.get("mb_strlen").asInt(),
                n.get("mb_substr_120").asString(),
                n.get("mb_strtolower").asString()));
      }
      return out.stream();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @ParameterizedTest(name = "[{index}] trim")
  @MethodSource("golden")
  @DisplayName("SC-IDENT-001/003 — PhpString.trim identique au trim PHP")
  void trim(String input, String trim, int len, String substr, String lower) {
    assertThat(PhpString.trim(input)).isEqualTo(trim);
  }

  @ParameterizedTest(name = "[{index}] mb_strlen")
  @MethodSource("golden")
  @DisplayName("SC-IDENT-003 — PhpString.length identique à mb_strlen")
  void length(String input, String trim, int len, String substr, String lower) {
    assertThat(PhpString.length(input)).isEqualTo(len);
  }

  @ParameterizedTest(name = "[{index}] mb_substr")
  @MethodSource("golden")
  @DisplayName("SC-IDENT-004 — PhpString.truncate(…, 120) identique à mb_substr(…, 0, 120)")
  void truncate(String input, String trim, int len, String substr, String lower) {
    assertThat(PhpString.truncate(input, 120)).isEqualTo(substr);
  }

  @ParameterizedTest(name = "[{index}] mb_strtolower")
  @MethodSource("golden")
  @DisplayName("SC-IDENT-001 — PhpString.toLower identique à mb_strtolower")
  void toLower(String input, String trim, int len, String substr, String lower) {
    assertThat(PhpString.toLower(input)).isEqualTo(lower);
  }
}
