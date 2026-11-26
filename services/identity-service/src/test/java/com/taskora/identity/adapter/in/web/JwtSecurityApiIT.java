package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.MariaDbContainerSupport;
import com.taskora.identity.support.TokenFactory;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/** Filtre JWT compatible Lexik (SC-IDENT-011, 012, 013) et accès publics. */
class JwtSecurityApiIT extends ApiTestBase {
  private static final String U = "camille@exemple.fr";

  private MvcResult meWithHeader(String header) throws Exception {
    return mvc.perform(get("/api/me").header("Authorization", header)).andReturn();
  }

  @Test
  @DisplayName(
      "SC-IDENT-011 — sans en-tête Authorization → 401 « JWT Token not found » + WWW-Authenticate: Bearer")
  void noHeader() throws Exception {
    assertAuthError(mvc.perform(get("/api/me")).andReturn(), "JWT Token not found");
  }

  @ParameterizedTest(name = "« {0} »")
  @ValueSource(
      strings = {
        "Basic Y2FtaWxsZTpwYXNz",
        "Bearer",
        "Bearer ",
        "Bearer a b",
        "Bearer  jeton",
        "Token abc",
        ""
      })
  @DisplayName(
      "SC-IDENT-011 — en-tête qui ne se découpe pas en « Bearer <jeton> » → « JWT Token not found »")
  void malformedHeader(String header) throws Exception {
    assertAuthError(meWithHeader(header), "JWT Token not found");
  }

  @Test
  @DisplayName("SC-IDENT-011 — préfixe insensible à la casse : « bearer <jeton> » accepté")
  void caseInsensitivePrefix() throws Exception {
    registeredToken("Camille", U, "motdepasse");
    assertThat(meWithHeader("bearer " + TOKENS.valid(U)).getResponse().getStatus()).isEqualTo(200);
    assertThat(meWithHeader("BEARER " + TOKENS.valid(U)).getResponse().getStatus()).isEqualTo(200);
  }

  static Stream<Arguments> protectedRoutes() {
    return Stream.of(
        Arguments.of(HttpMethod.GET, "/api/me"),
        Arguments.of(HttpMethod.PATCH, "/api/me"),
        Arguments.of(HttpMethod.GET, "/api/boards"),
        Arguments.of(HttpMethod.POST, "/api/boards"),
        Arguments.of(HttpMethod.GET, "/api/notifications"),
        Arguments.of(HttpMethod.DELETE, "/api/me"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protectedRoutes")
  @DisplayName("SC-IDENT-011 — toute route /api hors /api/auth exige un jeton (401)")
  void everyProtectedRoute(HttpMethod method, String path) throws Exception {
    MvcResult r = mvc.perform(request(method, path)).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(401);
    assertThat(r.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(json(r).get("message").asString()).isEqualTo("JWT Token not found");
  }

  @Test
  @DisplayName("SC-IDENT-011 — /api/auth/register et /api/auth/login accessibles sans jeton")
  void publicRoutes() throws Exception {
    assertThat(register("Camille", U, "motdepasse").getResponse().getStatus()).isEqualTo(201);
    assertThat(login(U, "motdepasse").getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("SC-IDENT-011 — un jeton invalide sur /api/auth/login n'empêche pas la connexion")
  void authRoutesIgnoreToken() throws Exception {
    registeredToken("Camille", U, "motdepasse");
    MvcResult r =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/auth/login")
                    .header("Authorization", "Bearer n.importe.quoi")
                    .contentType("application/json")
                    .content("{\"email\":\"camille@exemple.fr\",\"password\":\"motdepasse\"}"))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
  }

  static Stream<Arguments> invalidTokens() {
    return Stream.of(
        Arguments.of(
            "expiré", (Function<TokenFactory, String>) t -> t.expired(U), "Expired JWT Token"),
        Arguments.of(
            "expiré + signature fausse",
            (Function<TokenFactory, String>) t -> t.expiredWithBadSignature(U),
            "Expired JWT Token"),
        Arguments.of(
            "signature altérée",
            (Function<TokenFactory, String>) t -> t.badSignature(U),
            "Invalid JWT Token"),
        Arguments.of(
            "alg none", (Function<TokenFactory, String>) t -> t.algNone(U), "Invalid JWT Token"),
        Arguments.of(
            "HS256 + clé publique",
            (Function<TokenFactory, String>) t -> t.hs256WithPublicKey(U),
            "Invalid JWT Token"),
        Arguments.of(
            "autre clé",
            (Function<TokenFactory, String>) t -> t.signedByOtherKey(U),
            "Invalid JWT Token"),
        Arguments.of(
            "RS512", (Function<TokenFactory, String>) t -> t.rs512(U), "Invalid JWT Token"),
        Arguments.of(
            "exp absent",
            (Function<TokenFactory, String>) t -> t.withoutExp(U),
            "Invalid JWT Token"),
        Arguments.of(
            "iat futur", (Function<TokenFactory, String>) t -> t.futureIat(U), "Invalid JWT Token"),
        Arguments.of(
            "nbf futur", (Function<TokenFactory, String>) t -> t.futureNbf(U), "Invalid JWT Token"),
        Arguments.of(
            "illisible", (Function<TokenFactory, String>) t -> "pas.un.jeton", "Invalid JWT Token"),
        Arguments.of(
            "username absent",
            (Function<TokenFactory, String>) TokenFactory::withoutUsername,
            "Unable to find key \"username\" in the token payload."));
  }

  @ParameterizedTest(name = "{0} → {2}")
  @MethodSource("invalidTokens")
  @DisplayName(
      "SC-IDENT-012 — chaque jeton invalide → 401, message exact Lexik, WWW-Authenticate: Bearer")
  void invalidToken(String label, Function<TokenFactory, String> token, String message)
      throws Exception {
    registeredToken("Camille", U, "motdepasse");
    assertAuthError(me(token.apply(TOKENS)), message);
    MvcResult p =
        mvc.perform(
                patch("/api/me")
                    .header("Authorization", "Bearer " + token.apply(TOKENS))
                    .contentType("application/json")
                    .content("{\"name\":\"Pirate\"}"))
            .andReturn();
    assertThat(p.getResponse().getStatus()).isEqualTo(401);
    assertThat(query("SELECT name FROM identity.user_account").getFirst().get("name"))
        .isEqualTo("Camille");
  }

  @Test
  @DisplayName("SC-IDENT-010 — jeton au format Symfony (sans uid) accepté par identity")
  void symfonyToken() throws Exception {
    registeredToken("Camille", U, "motdepasse");
    MvcResult r = me(TOKENS.valid(U));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(json(r).get("email").asString()).isEqualTo(U);
  }

  @Test
  @DisplayName(
      "SC-IDENT-012 — jeton expiré au fil de l'horloge (tolérance 0) : accepté puis refusé")
  void expiresWithClock() throws Exception {
    String token = registeredToken("Camille", U, "motdepasse");
    assertThat(me(token).getResponse().getStatus()).isEqualTo(200);
    CLOCK.advance(java.time.Duration.ofSeconds(604_800));
    assertAuthError(me(token), "Expired JWT Token");
  }

  @Test
  @DisplayName("SC-IDENT-013 — jeton valide d'un compte inexistant → 401 « Invalid credentials. »")
  void unknownAccount() throws Exception {
    assertAuthError(me(TOKENS.valid("fantome@exemple.fr")), "Invalid credentials.");
  }

  @Test
  @DisplayName(
      "SC-IDENT-013 — compte supprimé après émission du jeton → 401 « Invalid credentials. »")
  void deletedAccount() throws Exception {
    String token = registeredToken("Camille", U, "motdepasse");
    MariaDbContainerSupport.rootExecute(
        List.of("DELETE FROM identity.user_account WHERE email = 'camille@exemple.fr'"));
    assertAuthError(me(token), "Invalid credentials.");
  }

  @Test
  @DisplayName("SC-IDENT-014 — IDOR : /api/me ne renvoie que le compte du jeton")
  void onlyOwnAccount() throws Exception {
    String a = registeredToken("Camille", U, "motdepasse");
    String b = registeredToken("Bob", "bob@exemple.fr", "motdepasse");
    assertThat(json(me(a)).get("email").asString()).isEqualTo(U);
    assertThat(json(me(b)).get("email").asString()).isEqualTo("bob@exemple.fr");
    assertThat(
            mvc.perform(get("/api/me?id=1").header("Authorization", "Bearer " + b))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains("bob@exemple.fr");
  }
}
