package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.application.port.in.LoginCommand;
import com.taskora.identity.domain.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SC-IDENT-032/033 (E19, E20) : règles françaises de l'inscription appliquées au login. */
class JsonLoginRequestParserTest {
  private static final String EMAIL_REQUIRED = "Le champ \"email\" est obligatoire.";
  private static final String PASSWORD_REQUIRED = "Le champ \"password\" est obligatoire.";
  private static final String EMAIL_TYPE = "Le champ \"email\" doit être une chaîne de caractères.";
  private static final String PASSWORD_TYPE =
      "Le champ \"password\" doit être une chaîne de caractères.";

  private static LoginCommand parse(String body) {
    return JsonLoginRequestParser.parse(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("SC-IDENT-007 — e-mail et mot de passe transmis bruts (ni trim, ni minuscules)")
  void raw() {
    LoginCommand c = parse("{\"email\":\" Camille@Exemple.fr \",\"password\":\" motdepasse \"}");
    assertThat(c.email()).isEqualTo(" Camille@Exemple.fr ");
    assertThat(c.password()).isEqualTo(" motdepasse ");
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          ''                                         | JSON invalide.
          '   '                                      | JSON invalide.
          '[]'                                       | JSON invalide.
          '"texte"'                                  | JSON invalide.
          '42'                                       | JSON invalide.
          'pas du json'                              | JSON invalide.
          '{"email":123,"password":"x"}'             | Le champ "email" doit être une chaîne de caractères.
          '{"email":null,"password":"x"}'            | Le champ "email" doit être une chaîne de caractères.
          '{"email":123}'                            | Le champ "email" doit être une chaîne de caractères.
          '{"email":"a@b.c","password":null}'        | Le champ "password" doit être une chaîne de caractères.
          '{"email":"a@b.c","password":["x"]}'       | Le champ "password" doit être une chaîne de caractères.
          '{"password":true}'                        | Le champ "password" doit être une chaîne de caractères.
          '{"password":"motdepasse"}'                | Le champ "email" est obligatoire.
          '{}'                                       | Le champ "email" est obligatoire.
          '{"email":"","password":"x"}'              | Le champ "email" est obligatoire.
          '{"email":"camille@exemple.fr"}'           | Le champ "password" est obligatoire.
          '{"email":"camille@exemple.fr","password":""}' | Le champ "password" est obligatoire.
          """)
  @DisplayName(
      "SC-IDENT-032/033 E19 E20 — messages français ; ordre forme → type email → type password → présence email → présence password")
  void rejects(String body, String message) {
    assertThatThrownBy(() -> parse(body))
        .isInstanceOf(ValidationException.class)
        .hasMessage(message);
  }

  @Test
  @DisplayName("SC-IDENT-032 E19 — aucun message anglais de json_login")
  void noEnglish() {
    assertThatThrownBy(() -> parse("{}"))
        .isInstanceOf(ValidationException.class)
        .message()
        .doesNotContain("must be")
        .doesNotContain("Invalid JSON");
  }
}
