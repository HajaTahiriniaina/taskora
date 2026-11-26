package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.support.TestKeys;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LexikKeyLoaderTest {
  @TempDir Path dir;

  @ParameterizedTest
  @EnumSource(TestKeys.Cipher.class)
  @DisplayName(
      "SC-IDENT-010 — clé privée PKCS#8 chiffrée PBES2 (3DES comme Lexik, AES-256) et clé publique lues")
  void loads(TestKeys.Cipher cipher) {
    TestKeys keys = TestKeys.generate(dir, cipher);
    LexikKeyLoader loader =
        new LexikKeyLoader(keys.privatePem(), keys.publicPem(), keys.passphrase().toCharArray());
    assertThat(loader.privateKey().getModulus()).isEqualTo(keys.privateKey().getModulus());
    assertThat(loader.privateKey().getPrivateExponent())
        .isEqualTo(keys.privateKey().getPrivateExponent());
    assertThat(loader.publicKey()).isEqualTo(keys.publicKey());
  }

  @ParameterizedTest
  @EnumSource(TestKeys.Cipher.class)
  @DisplayName(
      "SC-IDENT-024 — mauvaise passphrase → exception sans passphrase ni contenu PEM dans le message")
  void wrongPassphrase(TestKeys.Cipher cipher) {
    TestKeys keys = TestKeys.generate(dir, cipher);
    String wrong = "mauvaise-passphrase-" + keys.passphrase().substring(0, 6);
    assertThatThrownBy(() ->
            new LexikKeyLoader(keys.privatePem(), keys.publicPem(), wrong.toCharArray())
                .privateKey())
        .isNotInstanceOf(UnsupportedOperationException.class)
        .satisfies(e -> assertNoSecret(e, keys, wrong));
  }

  @ParameterizedTest
  @EnumSource(TestKeys.Cipher.class)
  @DisplayName("SC-IDENT-024 — fichier de clé illisible → exception sans contenu de clé")
  void unreadable(TestKeys.Cipher cipher) {
    TestKeys keys = TestKeys.generate(dir, cipher);
    assertThatThrownBy(() ->
            new LexikKeyLoader(
                    dir.resolve("absent.pem"), keys.publicPem(), keys.passphrase().toCharArray())
                .privateKey())
        .isNotInstanceOf(UnsupportedOperationException.class)
        .satisfies(e -> assertNoSecret(e, keys, keys.passphrase()));
  }

  private static void assertNoSecret(Throwable e, TestKeys keys, String passphrase) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String m = String.valueOf(t.getMessage());
      assertThat(m)
          .doesNotContain(passphrase)
          .doesNotContain(keys.passphrase())
          .doesNotContain("BEGIN");
      String pemBody =
          keys.privatePemText()
              .lines()
              .filter(l -> !l.startsWith("-----"))
              .findFirst()
              .orElseThrow();
      assertThat(m).doesNotContain(pemBody.substring(0, 20));
    }
  }
}
