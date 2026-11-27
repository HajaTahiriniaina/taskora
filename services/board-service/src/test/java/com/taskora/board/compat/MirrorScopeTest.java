package com.taskora.board.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Garde-fou exécuté dans le build par défaut (sans stack, comme MirrorScopeTest d'identity) :
 * chaque opération T1 du contrat est couverte par le miroir ; la liste des divergences voulues est
 * exactement celle de l'ordre de travail §1.4 ; aucun corps du miroir ne relève d'une divergence
 * (U13, E22, E23).
 */
class MirrorScopeTest {
  private static final Path CONTRACT = Path.of("../../docs/contracts/openapi/board-service.yaml");

  @Test
  @DisplayName("SC-BOARD-029 — chaque opération T1 du contrat est couverte par le miroir")
  void everyOperationMirrored() {
    OpenAPI api = new OpenAPIV3Parser().read(CONTRACT.toUri().toString());
    Set<String> ops = new HashSet<>();
    api.getPaths()
        .values()
        .forEach(p -> p.readOperations().forEach(o -> ops.add(o.getOperationId())));
    assertThat(BoardMirrorCompatibilityTest.MIRRORED_OPERATIONS)
        .containsExactlyInAnyOrderElementsOf(ops);
  }

  @Test
  @DisplayName(
      "§1.4 — divergences voulues : U13, E12, E22, E23, E28, concurrence ; rien d'autre (E1 comparé, U8-a)")
  void divergencesAreExactlyTheDecidedOnes() {
    assertThat(Set.copyOf(BoardMirrorCompatibilityTest.INTENTIONAL_DIVERGENCES.values()))
        .containsExactlyInAnyOrder("U13", "E12", "E22", "E23", "E28", "CONCURRENCE");
    assertThat(BoardMirrorCompatibilityTest.INTENTIONAL_DIVERGENCES.keySet())
        .noneMatch(k -> k.startsWith("E1-"));
  }

  @Test
  @DisplayName("U13, E22, E23 — les corps du miroir sont des objets aux types conformes")
  void mirrorBodiesAvoidDivergences() {
    JsonMapper json = JsonMapper.builder().build();
    for (String raw : BoardMirrorCompatibilityTest.MIRROR_BODIES) {
      JsonNode n = json.readTree(raw.replace("%s", "x@exemple.test"));
      assertThat(n.isObject()).as("U13 : %s", raw).isTrue();
      for (String text : new String[] {"title", "description", "email"}) {
        if (n.has(text)) {
          assertThat(n.get(text).isString()).as("E22 : %s", raw).isTrue();
        }
      }
      for (String bool : new String[] {"withDefaults", "showCardNumbers"}) {
        if (n.has(bool)) {
          assertThat(n.get(bool).isBoolean()).as("E23 : %s", raw).isTrue();
        }
      }
    }
  }
}
