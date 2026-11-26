package com.taskora.identity.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PersonName;
import com.taskora.identity.domain.model.Roles;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import com.taskora.identity.support.MutableClock;
import com.taskora.identity.support.TestKeys;
import com.taskora.identity.support.TokenFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NimbusTokenIssuerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path dir;
  private TestKeys keys;
  private MutableClock clock;
  private NimbusTokenIssuer issuer;

  @BeforeEach
  void setUp() {
    keys = TestKeys.generate(dir, TestKeys.Cipher.DES3);
    clock = new MutableClock(Instant.parse("2026-11-26T09:00:00Z"));
    issuer =
        new NimbusTokenIssuer(
            new LexikKeyLoader(
                keys.privatePem(), keys.publicPem(), keys.passphrase().toCharArray()),
            clock,
            Duration.ofSeconds(604_800));
  }

  private static User camille(List<String> roles) {
    return User.rehydrate(
        new UserId(4),
        Email.ofStored("camille@exemple.fr"),
        PersonName.of("Camille"),
        new HashedPassword("$2y$04$abcdefghijklmnopqrstuuWj1z8m5eT8Yb8eF6l2hYxE6m8dGJQ2K"),
        AvatarColor.parse("#14b8a6"),
        new Roles(roles),
        LocalDateTime.of(2026, 11, 26, 10, 0));
  }

  @Test
  @DisplayName("SC-IDENT-010 — en-tête exactement {typ: JWT, alg: RS256}, sans kid")
  void header() {
    JsonNode h = JSON.readTree(TokenFactory.decodePart(issuer.issue(camille(List.of())), 0));
    List<String> names = new ArrayList<>(h.propertyNames());
    assertThat(names).containsExactlyInAnyOrder("typ", "alg");
    assertThat(h.get("typ").asString()).isEqualTo("JWT");
    assertThat(h.get("alg").asString()).isEqualTo("RS256");
  }

  @Test
  @DisplayName(
      "SC-IDENT-010 — revendications : iat/exp entiers, exp − iat = 604800, username, roles, uid, rien d'autre")
  void claims() {
    JsonNode c = JSON.readTree(TokenFactory.decodePart(issuer.issue(camille(List.of())), 1));
    assertThat(new ArrayList<>(c.propertyNames()))
        .containsExactlyInAnyOrder("iat", "exp", "roles", "username", "uid");
    assertThat(c.get("iat").isIntegralNumber()).isTrue();
    assertThat(c.get("exp").isIntegralNumber()).isTrue();
    assertThat(c.get("iat").asLong()).isEqualTo(clock.epochSecond());
    assertThat(c.get("exp").asLong() - c.get("iat").asLong()).isEqualTo(604_800L);
    assertThat(c.get("username").asString()).isEqualTo("camille@exemple.fr");
    assertThat(c.get("uid").isIntegralNumber()).isTrue();
    assertThat(c.get("uid").asInt()).isEqualTo(4);
    assertThat(c.get("roles").isArray()).isTrue();
    assertThat(c.get("roles").get(0).asString()).isEqualTo("ROLE_USER");
    assertThat(c.get("roles").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-010 — rôles stockés + ROLE_USER, ordre de User::getRoles()")
  void storedRoles() {
    JsonNode c =
        JSON.readTree(TokenFactory.decodePart(issuer.issue(camille(List.of("ROLE_ADMIN"))), 1));
    assertThat(c.get("roles").get(0).asString()).isEqualTo("ROLE_ADMIN");
    assertThat(c.get("roles").get(1).asString()).isEqualTo("ROLE_USER");
  }

  @Test
  @DisplayName("SC-IDENT-010 — signature RS256 vérifiable avec la clé publique Lexik")
  void signature() throws Exception {
    String[] parts = issuer.issue(camille(List.of())).split("\\.");
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initVerify(keys.publicKey());
    s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(s.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  @DisplayName("SC-IDENT-010 — iat suit l'horloge injectée")
  void followsClock() {
    clock.advance(Duration.ofHours(1));
    JsonNode c = JSON.readTree(TokenFactory.decodePart(issuer.issue(camille(List.of())), 1));
    assertThat(c.get("iat").asLong())
        .isEqualTo(Instant.parse("2026-11-26T10:00:00Z").getEpochSecond());
  }
}
