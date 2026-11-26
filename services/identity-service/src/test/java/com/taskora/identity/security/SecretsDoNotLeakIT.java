package com.taskora.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SC-IDENT-024 : aucun mot de passe, hash, jeton, passphrase ni PEM dans les réponses et les
 * journaux.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "logging.level.com.taskora=DEBUG",
      "logging.level.org.springframework.security=DEBUG"
    })
class SecretsDoNotLeakIT extends ApiTestBase {
  private static final String PW1 = "Secret-Initial-7f3a";
  private static final String PW2 = "Secret-Nouveau-9c1e";

  @Test
  @DisplayName(
      "SC-IDENT-024 — parcours inscription → login → PATCH mot de passe → erreurs : aucune fuite")
  void noLeak(CapturedOutput output) throws Exception {
    List<MvcResult> responses = new ArrayList<>();
    MvcResult reg = register("Camille", "camille@exemple.fr", PW1);
    assertThat(reg.getResponse().getStatus()).as("inscription : %s", body(reg)).isEqualTo(201);
    responses.add(reg);
    String token1 = json(reg).get("token").asString();
    String hash1 = storedHash("camille@exemple.fr");
    MvcResult log = login("camille@exemple.fr", PW1);
    assertThat(log.getResponse().getStatus()).as("connexion : %s", body(log)).isEqualTo(200);
    responses.add(log);
    String token2 = json(log).get("token").asString();
    responses.add(
        patchMe(token2, "{\"currentPassword\":\"" + PW1 + "\",\"newPassword\":\"" + PW2 + "\"}"));
    String hash2 = storedHash("camille@exemple.fr");
    responses.add(login("camille@exemple.fr", PW1)); // 401
    responses.add(
        patchMe(
            token2,
            "{\"currentPassword\":\"" + PW1 + "\",\"newPassword\":\"" + PW2 + "x\"}")); // 400
    responses.add(
        patchMe(
            token2,
            "{\"currentPassword\":\""
                + PW2
                + "\",\"newPassword\":\""
                + "z".repeat(4097)
                + "\"}")); // 400 E15
    responses.add(register("Camille", "camille@exemple.fr", PW1)); // 409
    responses.add(me(TOKENS.badSignature("camille@exemple.fr"))); // 401
    responses.add(me(TOKENS.expired("camille@exemple.fr"))); // 401
    responses.add(postJson("/api/auth/register", "{\"name\":")); // 400

    List<String> secrets =
        new ArrayList<>(
            List.of(PW1, PW2, hash1, hash2, token1, token2, KEYS.passphrase(), "z".repeat(100)));
    secrets.add(hash1.substring(7)); // sel + empreinte bcrypt sans préfixe
    secrets.add(token1.split("\\.")[2]); // signature du jeton
    KEYS.privatePemText()
        .lines()
        .filter(l -> !l.startsWith("-----"))
        .limit(3)
        .forEach(secrets::add);

    for (MvcResult r : responses) {
      String b = body(r);
      String b2 = b.replace(token1, "").replace(token2, "");
      for (String s : secrets) {
        if (s.equals(token1) || s.equals(token2) || s.equals(token1.split("\\.")[2])) {
          continue; // un jeton n'apparaît que dans la réponse qui l'émet
        }
        assertThat(b2).as("réponse %s", r.getRequest().getRequestURI()).doesNotContain(s);
      }
      assertThat(b).doesNotContain("Exception").doesNotContain("\tat ");
    }
    String logs = output.getAll();
    for (String s : secrets) {
      assertThat(logs).as("journaux").doesNotContain(s);
    }
    assertThat(logs).doesNotContain("BEGIN ENCRYPTED PRIVATE KEY").doesNotContain("PRIVATE KEY");
    assertThat(query("SELECT last_error FROM identity.outbox_message WHERE last_error IS NOT NULL"))
        .allSatisfy(
            row -> assertThat(String.valueOf(row.get("last_error"))).doesNotContain("camille"));
  }

  @Test
  @DisplayName("SC-IDENT-024 — les e-mails ne sont pas journalisés en INFO")
  void noEmailAtInfo(CapturedOutput output) throws Exception {
    assertThat(register("Camille", "journal-info@exemple.fr", PW1).getResponse().getStatus())
        .isEqualTo(201);
    assertThat(login("journal-info@exemple.fr", PW1).getResponse().getStatus()).isEqualTo(200);
    assertThat(output.getAll().lines().filter(l -> l.contains(" INFO ")))
        .noneMatch(l -> l.contains("journal-info@exemple.fr"));
  }
}
