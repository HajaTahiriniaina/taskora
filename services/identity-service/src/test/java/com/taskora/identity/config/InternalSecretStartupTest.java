package com.taskora.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
 * SC-IDENT-036 (ADR A18) : identity-service refuse de démarrer si identity.symfony.internal-secret
 * (INTERNAL_API_SECRET) est absent, vide ou composé d'espaces ; le message nomme la variable,
 * jamais la valeur ni un autre secret.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalSecretStartupTest {
  private static final String MESSAGE = "INTERNAL_API_SECRET doit être défini et non vide.";
  private static final String PASSPHRASE = "passphrase-de-test-9f2c41";

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(IdentityProperties.class)
  static class PropertiesOnly {}

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnly.class)
        .withPropertyValues(
            "identity.jwt.private-key-path=/tmp/absent/private.pem",
            "identity.jwt.public-key-path=/tmp/absent/public.pem",
            "identity.jwt.passphrase=" + PASSPHRASE,
            "identity.jwt.ttl=604800",
            "identity.legacy.schema=taskora",
            "identity.legacy.zone=Europe/Paris",
            "identity.symfony.internal-url=http://api:80",
            "identity.password.bcrypt-cost=4",
            "identity.outbox.relay.enabled=false");
  }

  /**
   * Injecte la valeur telle quelle (withPropertyValues retire les espaces : un secret « blanc » y
   * deviendrait vide).
   */
  private static ApplicationContextRunner withSecret(String secret) {
    return runner()
        .withInitializer(context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "secret", Map.of("identity.symfony.internal-secret", secret))));
  }

  private static String causeMessages(Throwable failure) {
    List<String> messages = new ArrayList<>();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.add(String.valueOf(t.getMessage()));
    }
    return String.join("\n", messages);
  }

  @ParameterizedTest(name = "secret = \"{0}\"")
  @ValueSource(strings = {"", " ", "   ", "\t", " \t \n "})
  @DisplayName(
      "SC-IDENT-036 — secret vide ou blanc → démarrage refusé, message nommant INTERNAL_API_SECRET")
  void blankSecretRefused(String secret, CapturedOutput output) {
    withSecret(secret)
        .run(context -> {
          assertThat(context).hasFailed();
          String messages = causeMessages(context.getStartupFailure());
          assertThat(messages).contains(MESSAGE).doesNotContain(PASSPHRASE);
        });
    assertThat(output.getAll()).doesNotContain(PASSPHRASE);
  }

  @Test
  @DisplayName(
      "SC-IDENT-036 — secret absent → démarrage refusé, message nommant INTERNAL_API_SECRET")
  void absentSecretRefused(CapturedOutput output) {
    runner()
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(causeMessages(context.getStartupFailure()))
              .contains(MESSAGE)
              .doesNotContain(PASSPHRASE);
        });
    assertThat(output.getAll()).doesNotContain(PASSPHRASE);
  }

  @Test
  @DisplayName(
      "SC-IDENT-036 — secret non vide → démarrage réussi, valeur liée telle quelle, absente de toString")
  void nonBlankSecretStarts() {
    String secret = "test-internal-secret-0123456789abcdef";
    withSecret(secret)
        .run(context -> {
          assertThat(context).hasNotFailed();
          IdentityProperties p = context.getBean(IdentityProperties.class);
          assertThat(p.symfony().internalSecret()).isEqualTo(secret);
          assertThat(p.toString()).doesNotContain(secret);
        });
  }
}
