package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.support.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * B1 (ordre de travail phase-1-identity-perf-b1 §5.1, point 2) : {@code
 * identity.password.bcrypt-engine=java} sélectionne le moteur Java pur (développement hors Linux).
 */
class BcryptEngineJavaSelectableIT extends ApiTestBase {

  @Autowired private PasswordHasherPort hasher;

  @DynamicPropertySource
  static void javaEngine(DynamicPropertyRegistry r) {
    r.add("identity.password.bcrypt-engine", () -> "java");
  }

  @Test
  @DisplayName("B1 — identity.password.bcrypt-engine=java : moteur Java sélectionné")
  void javaEngineSelected() {
    assertThat(hasher).isInstanceOf(SymfonyCompatiblePasswordHasher.class);
    assertThat(((SymfonyCompatiblePasswordHasher) hasher).engine())
        .isInstanceOf(JavaBcryptEngine.class);
  }

  @Test
  @DisplayName("B1 / SC-IDENT-023 — moteur java : inscription puis connexion, hash $2y$")
  void registerThenLoginOnJavaEngine() throws Exception {
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(storedHash("camille@exemple.fr")).startsWith("$2y$04$").hasSize(60);
    assertThat(login("camille@exemple.fr", "motdepasse").getResponse().getStatus()).isEqualTo(200);
  }
}
