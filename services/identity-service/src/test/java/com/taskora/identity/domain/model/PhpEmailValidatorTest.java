package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden : compat/php-filter-validate-email.json, produit par filter_var(FILTER_VALIDATE_EMAIL) en
 * PHP 8.4.
 */
class PhpEmailValidatorTest {

  static List<JsonNode> cases() {
    try (InputStream in =
        PhpEmailValidatorTest.class.getResourceAsStream("/compat/php-filter-validate-email.json")) {
      List<JsonNode> out = new ArrayList<>();
      JsonMapper.builder().build().readTree(in).forEach(out::add);
      return out;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Stream<Arguments> golden() {
    return cases().stream()
        .map(n -> Arguments.of(n.get("input").asString(), n.get("valid").asBoolean()));
  }

  @Test
  @DisplayName("SC-IDENT-003 — le golden contient au moins 150 cas, valides et invalides")
  void goldenIsLargeEnough() {
    List<JsonNode> all = cases();
    assertThat(all).hasSizeGreaterThanOrEqualTo(150);
    assertThat(all.stream().filter(n -> n.get("valid").asBoolean())).isNotEmpty();
    assertThat(all.stream().filter(n -> !n.get("valid").asBoolean())).isNotEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0} → {1}")
  @MethodSource("golden")
  @DisplayName("SC-IDENT-003 — identique à filter_var(FILTER_VALIDATE_EMAIL) de PHP")
  void sameAsPhp(String input, boolean valid) {
    assertThat(PhpEmailValidator.isValid(input)).isEqualTo(valid);
  }
}
