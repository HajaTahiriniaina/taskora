package com.taskora.board.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * A18, U12 (B-ADR-5 §6) — board-service refuse de démarrer si {@code
 * INTERNAL_SECRET_IDENTITY_TO_BOARD} ou {@code INTERNAL_SECRET_BOARD_TO_SYMFONY} est absent, vide
 * ou blanc, ou si les deux sont égaux. Le message nomme la variable, jamais une valeur.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalSecretRequiredTest {
  private static final String IDENTITY_TO_BOARD = "secret-identite-vers-board-0123456789abcdef";
  private static final String BOARD_TO_SYMFONY = "secret-board-vers-symfony-fedcba9876543210";

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BoardProperties.class)
  static class PropertiesOnly {}

  /** Valeurs injectées telles quelles (withPropertyValues retirerait les espaces). */
  private static ApplicationContextRunner runner(String identityToBoard, String boardToSymfony) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("board.jwt.public-key-path", "/tmp/absent/public.pem");
    props.put("board.legacy.schema", "taskora");
    props.put("board.legacy.zone", "Europe/Paris");
    props.put("board.symfony.internal-url", "http://api:80");
    props.put("board.outbox.relay.enabled", "false");
    props.put("board.routing.marker", "false");
    props.put("board.purge.enabled", "false");
    if (identityToBoard != null) {
      props.put("board.internal.identity-to-board-secret", identityToBoard);
    }
    if (boardToSymfony != null) {
      props.put("board.internal.board-to-symfony-secret", boardToSymfony);
    }
    return new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnly.class)
        .withInitializer(context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("t", props)));
  }

  private static String causeMessages(Throwable failure) {
    List<String> messages = new ArrayList<>();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.add(String.valueOf(t.getMessage()));
    }
    return String.join("\n", messages);
  }

  @ParameterizedTest(name = "secret identity → board = \"{0}\"")
  @ValueSource(strings = {"", " ", "\t", " \t \n "})
  @DisplayName(
      "A18/U12 — INTERNAL_SECRET_IDENTITY_TO_BOARD vide ou blanc → démarrage refusé, variable nommée")
  void identityToBoardBlank(String secret, CapturedOutput output) {
    runner(secret, BOARD_TO_SYMFONY)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains("INTERNAL_SECRET_IDENTITY_TO_BOARD")
              .doesNotContain(BOARD_TO_SYMFONY);
        });
    assertThat(output.getAll()).doesNotContain(BOARD_TO_SYMFONY);
  }

  @ParameterizedTest(name = "secret board → Symfony = \"{0}\"")
  @ValueSource(strings = {"", " ", "\t", " \t \n "})
  @DisplayName(
      "A18/U12 — INTERNAL_SECRET_BOARD_TO_SYMFONY vide ou blanc → démarrage refusé, variable nommée")
  void boardToSymfonyBlank(String secret, CapturedOutput output) {
    runner(IDENTITY_TO_BOARD, secret)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains("INTERNAL_SECRET_BOARD_TO_SYMFONY")
              .doesNotContain(IDENTITY_TO_BOARD);
        });
    assertThat(output.getAll()).doesNotContain(IDENTITY_TO_BOARD);
  }

  @Test
  @DisplayName("A18/U12 — secrets absents → démarrage refusé")
  void absent(CapturedOutput output) {
    runner(null, BOARD_TO_SYMFONY)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains("INTERNAL_SECRET_IDENTITY_TO_BOARD");
        });
    runner(IDENTITY_TO_BOARD, null)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains("INTERNAL_SECRET_BOARD_TO_SYMFONY");
        });
    assertThat(output.getAll()).doesNotContain(IDENTITY_TO_BOARD).doesNotContain(BOARD_TO_SYMFONY);
  }

  @Test
  @DisplayName("U12 — les deux secrets égaux → démarrage refusé, sans la valeur")
  void equalSecrets(CapturedOutput output) {
    runner(IDENTITY_TO_BOARD, IDENTITY_TO_BOARD)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains("INTERNAL_SECRET_IDENTITY_TO_BOARD")
              .contains("INTERNAL_SECRET_BOARD_TO_SYMFONY")
              .doesNotContain(IDENTITY_TO_BOARD);
        });
    assertThat(output.getAll()).doesNotContain(IDENTITY_TO_BOARD);
  }

  @Test
  @DisplayName("U12 — deux secrets distincts → démarrage ; valeurs liées, absentes de toString")
  void distinctSecretsStart() {
    runner(IDENTITY_TO_BOARD, BOARD_TO_SYMFONY)
        .run(context -> {
          assertThat(context).hasNotFailed();
          BoardProperties p = context.getBean(BoardProperties.class);
          assertThat(p.internal().identityToBoardSecret()).isEqualTo(IDENTITY_TO_BOARD);
          assertThat(p.internal().boardToSymfonySecret()).isEqualTo(BOARD_TO_SYMFONY);
          assertThat(p.toString())
              .doesNotContain(IDENTITY_TO_BOARD)
              .doesNotContain(BOARD_TO_SYMFONY);
          assertThat(p.internal().toString())
              .doesNotContain(IDENTITY_TO_BOARD)
              .doesNotContain(BOARD_TO_SYMFONY);
        });
  }
}
