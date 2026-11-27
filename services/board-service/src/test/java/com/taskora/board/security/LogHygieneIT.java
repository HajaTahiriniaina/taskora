package com.taskora.board.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.SymfonyStub;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SC-BOARD-026 — journaux capturés d'un parcours T1 complet (succès, refus, erreurs) : 0 jeton, 0
 * secret, 0 mot de passe ; aucun e-mail dans une ligne INFO ; ni titre ni description ; aucune 5xx.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIT extends ApiTestBase {
  private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.eyJ[\\w-]+");
  private static final String TITLE_MARKER = "TitreSecretMarqueur" + UUID.randomUUID();
  private static final String DESCRIPTION_MARKER = "DescriptionMarqueur" + UUID.randomUUID();

  @Test
  @DisplayName(
      "SC-BOARD-026 — parcours T1 : 0 jeton, secret ou mot de passe ; 0 e-mail en INFO ; 0 contenu ; 0 × 5xx")
  void journey(CapturedOutput output) {
    List<Response> responses = new ArrayList<>();
    String alice = alice();
    Response created =
        post(
            alice,
            "/api/boards",
            "{\"title\":\"" + TITLE_MARKER + "\",\"description\":\"" + DESCRIPTION_MARKER + "\"}");
    responses.add(created);
    int id = json(created).get("id").asInt();
    String board = "/api/boards/" + id;
    responses.add(get(alice, "/api/boards"));
    responses.add(get(alice, board));
    responses.add(patch(alice, board, "{\"description\":\"" + DESCRIPTION_MARKER + "-2\"}"));
    responses.add(post(alice, board + "/favorite", null));
    responses.add(
        post(alice, board + "/members", "{\"email\":\"dora@exemple.test\",\"role\":\"admin\"}"));
    responses.add(
        post(alice, board + "/members", "{\"email\":\"nouvelle.personne@exemple.test\"}"));
    responses.add(post(alice, board + "/members", "{\"email\":\"dora@exemple.test\"}"));
    responses.add(post(alice, board + "/members", "{\"email\":\"pas-un-email\"}"));
    responses.add(patch(alice, board + "/members/" + Fixture.DORA, "{\"role\":\"member\"}"));
    responses.add(delete(alice, board + "/members/" + Fixture.DORA));
    responses.add(get(nadia(), board));
    responses.add(get(TOKENS.expired(Fixture.ALICE_EMAIL), "/api/boards"));
    responses.add(get(TOKENS.badSignature(Fixture.ALICE_EMAIL), "/api/boards"));
    responses.add(get(TOKENS.symfony("disparu@exemple.test"), "/api/boards"));
    responses.add(post(alice, "/api/boards", "pas du json"));
    responses.add(delete(alice, board));
    responses.add(get(alice, "/api/trash/boards"));
    responses.add(post(alice, "/api/trash/boards/" + id + "/restore", null));
    responses.add(
        claim(
            IDENTITY_TO_BOARD_SECRET,
            "{\"eventId\":\""
                + UUID.randomUUID()
                + "\",\"userId\":9,\"email\":\"invite@exemple.test\"}"));
    responses.add(claim("mauvais-secret-0123456789abcdef0123", "{}"));
    await().atMost(Duration.ofSeconds(10)).until(() -> SymfonyStub.notifications().size() >= 2);

    assertThat(responses)
        .allSatisfy(r -> assertThat(r.statusCode()).as(r.asString()).isLessThan(500));
    String all = output.getAll();
    assertThat(JWT.matcher(all).find()).as("aucun jeton dans les journaux").isFalse();
    assertThat(all)
        .doesNotContain(IDENTITY_TO_BOARD_SECRET)
        .doesNotContain(BOARD_TO_SYMFONY_SECRET)
        .doesNotContain("mauvais-secret-0123456789abcdef0123")
        .doesNotContain(BoardDatabase.BOARD_PASSWORD)
        .doesNotContain(TOKENS.passphrase())
        .doesNotContain("$2y$13$")
        .doesNotContain("Using generated security password")
        .doesNotContain(TITLE_MARKER)
        .doesNotContain(DESCRIPTION_MARKER);
    for (String line : all.split("\\R")) {
      if (line.contains(" INFO ")) {
        assertThat(line).as("e-mail en INFO").doesNotContainPattern("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
      }
    }
  }
}
