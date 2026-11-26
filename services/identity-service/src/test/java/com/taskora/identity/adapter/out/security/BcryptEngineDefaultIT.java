package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.support.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B1 (ordre de travail phase-1-identity-perf-b1 §5.1, point 2) : sans réglage, le hacheur du
 * contexte Spring utilise le moteur bcrypt natif, pour le vrai hash comme pour le hash factice.
 */
class BcryptEngineDefaultIT extends ApiTestBase {

  @Autowired private PasswordHasherPort hasher;

  @Test
  @DisplayName(
      "B1 — contexte Spring sans identity.password.bcrypt-engine : moteur natif (libxcrypt) par défaut")
  void nativeEngineByDefault() {
    assertThat(hasher).isInstanceOf(SymfonyCompatiblePasswordHasher.class);
    assertThat(((SymfonyCompatiblePasswordHasher) hasher).engine())
        .isInstanceOf(LibxcryptBcryptEngine.class);
  }

  @Test
  @DisplayName("B1 / SC-IDENT-023 — moteur par défaut : inscription puis connexion, hash $2y$")
  void registerThenLoginOnDefaultEngine() throws Exception {
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(storedHash("camille@exemple.fr")).startsWith("$2y$04$").hasSize(60);
    assertThat(login("camille@exemple.fr", "motdepasse").getResponse().getStatus()).isEqualTo(200);
    assertThat(login("camille@exemple.fr", "mauvaismdp").getResponse().getStatus()).isEqualTo(401);
  }
}
