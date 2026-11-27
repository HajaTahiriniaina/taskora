package com.taskora.board.config;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-020, B-ADR-2 point 1, U12 — board-service ne lit que la clé publique : ni clé privée ni
 * passphrase dans sa configuration, son code ou son environnement ; il ne lit jamais {@code
 * INTERNAL_API_SECRET} (secret d'identity vers Symfony).
 */
class BoardSecretsConfigTest {
  private static final Path MAIN_JAVA = Path.of("src/main/java");
  private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");

  private static void collectComponents(Class<?> type, String prefix, List<String> out) {
    if (!type.isRecord()) {
      return;
    }
    for (RecordComponent c : type.getRecordComponents()) {
      out.add(prefix + c.getName());
      collectComponents(c.getType(), prefix + c.getName() + ".", out);
    }
  }

  @Test
  @DisplayName("B-ADR-2 — BoardProperties n'a ni clé privée ni passphrase")
  void propertiesHaveNoPrivateMaterial() {
    List<String> names = new ArrayList<>();
    collectComponents(BoardProperties.class, "", names);
    assertThat(names).contains("jwt.publicKeyPath");
    assertThat(names)
        .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("private"))
        .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("passphrase"));
  }

  @Test
  @DisplayName(
      "B-ADR-2 / U12 — application.yml : clé publique et deux secrets par appelant, jamais JWT_PASSPHRASE, JWT_PRIVATE_KEY_PATH ni INTERNAL_API_SECRET")
  void applicationYml() throws IOException {
    assertThat(Files.exists(APPLICATION_YML))
        .as("src/main/resources/application.yml (BackCodeur)")
        .isTrue();
    List<String> effective =
        Files.readAllLines(APPLICATION_YML).stream()
            .map(l -> l.contains("#") ? l.substring(0, l.indexOf('#')) : l)
            .toList();
    String yml = String.join("\n", effective);
    assertThat(yml)
        .contains("JWT_PUBLIC_KEY_PATH")
        .contains("INTERNAL_SECRET_IDENTITY_TO_BOARD")
        .contains("INTERNAL_SECRET_BOARD_TO_SYMFONY")
        .contains("BOARD_DB_PASSWORD")
        .doesNotContain("JWT_PASSPHRASE")
        .doesNotContain("JWT_PRIVATE_KEY_PATH")
        .doesNotContain("INTERNAL_API_SECRET");
  }

  @Test
  @DisplayName(
      "B-ADR-2 / U12 — aucune chaîne du code ne cite JWT_PASSPHRASE, JWT_PRIVATE_KEY_PATH, INTERNAL_API_SECRET ni une clé privée PEM")
  void sourcesNeverReadForbiddenVariables() throws IOException {
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher m = STRING_LITERAL.matcher(Files.readString(f));
        while (m.find()) {
          String lit = m.group();
          if (lit.contains("JWT_PASSPHRASE")
              || lit.contains("JWT_PRIVATE_KEY_PATH")
              || lit.contains("INTERNAL_API_SECRET")
              || lit.contains("PRIVATE KEY")
              || lit.toLowerCase(Locale.ROOT).contains("private-key-path")) {
            offenders.add(f + " : " + lit);
          }
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("B-ADR-2 — aucune classe livrée ne manipule une clé privée")
  void noPrivateKeyTypes() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.taskora.board");
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.security.PrivateKey")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.security.interfaces.RSAPrivateKey")
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("org.bouncycastle..")
        .as("aucune clé privée ni BouncyCastle (déchiffrement PKCS#8) dans board-service")
        .check(classes);
  }
}
