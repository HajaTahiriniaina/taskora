package com.taskora.identity.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Garde-fou exécuté dans le build par défaut (sans stack) : le périmètre miroir n'envoie aucune
 * requête relevant des divergences voulues E15–E21, et la liste des divergences ne contient rien
 * d'autre.
 */
class MirrorScopeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @DisplayName("E15–E21 — la liste des divergences voulues ne contient que E15 à E21")
  void divergencesAreOnlyDecidedOnes() {
    assertThat(Set.copyOf(MirrorCompatibilityTest.INTENTIONAL_DIVERGENCES.values()))
        .containsExactlyInAnyOrder("E15", "E16", "E17", "E18", "E19", "E20", "E21");
    assertThat(MirrorCompatibilityTest.INTENTIONAL_DIVERGENCES.keySet()).allMatch(k -> k.matches("E(1[5-9]|2[01])-.+"));
  }

  @Test
  @DisplayName("E15–E21 — aucun cas miroir ne relève d'une divergence voulue")
  void mirrorExcludesDivergences() {
    for (MirrorCompatibilityTest.Case c : MirrorCompatibilityTest.MIRROR) {
      String b = c.body() == null ? null : c.body();
      assertThat(b).as("%s : E20 corps vide", c.id()).isNotEqualTo("");
      if (b != null && !b.equals("texte")) {
        JsonNode n = JSON.readTree(b.replace("${EMAIL_UPPER}", "x").replace("${EMAIL}", "x"));
        assertThat(n.isObject()).as("%s : E16", c.id()).isTrue();
        n.properties().forEach(e -> {
          boolean known = Set.of("name", "email", "password", "avatarColor", "currentPassword", "newPassword").contains(e.getKey());
          boolean nullOnPatch = c.method().equals("PATCH") && e.getValue().isNull();
          if (known && !nullOnPatch) {
            assertThat(e.getValue().isString()).as("%s : E17 (%s)", c.id(), e.getKey()).isTrue();
          }
        });
        if (n.has("avatarColor")) {
          assertThat(n.get("avatarColor").asString()).as("%s : E18", c.id()).matches("#[0-9a-fA-F]{6}");
        }
        if (n.has("newPassword")) {
          assertThat(n.get("newPassword").asString()).as("%s : E21", c.id()).isNotEqualTo("0");
        }
        for (String pw : List.of("password", "newPassword")) {
          if (n.has(pw) && n.get(pw).isString()) {
            assertThat(n.get(pw).asString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .as("%s : E15", c.id()).isLessThanOrEqualTo(4096);
          }
        }
      }
      if (c.path().equals("/api/auth/login")) {
        assertThat(c.id()).as("E19 : aucun 400 de login en miroir").doesNotContain("SC-IDENT-009");
        assertThat(b).contains("\"email\":\"").contains("\"password\":\"");
      }
    }
  }

}
