package com.taskora.identity.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jetons au format Lexik (en-tête {"typ":"JWT","alg":"RS256"}, iat/exp entiers, roles, username) et
 * leurs variantes malveillantes ou invalides (SC-IDENT-010, 012).
 */
public final class TokenFactory {
  public static final long TTL = 604_800L;
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final TestKeys keys;
  private final MutableClock clock;

  public TokenFactory(TestKeys keys, MutableClock clock) {
    this.keys = keys;
    this.clock = clock;
  }

  public static Map<String, Object> lexikHeader() {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "RS256");
    return h;
  }

  /** Revendications émises par Symfony aujourd'hui (pas de uid). */
  public Map<String, Object> symfonyClaims(String username) {
    Map<String, Object> c = new LinkedHashMap<>();
    long now = clock.epochSecond();
    c.put("iat", now);
    c.put("exp", now + TTL);
    c.put("roles", List.of("ROLE_USER"));
    c.put("username", username);
    return c;
  }

  /** Jeton Symfony valide. */
  public String valid(String username) {
    return rs256(lexikHeader(), symfonyClaims(username), keys.privateKey());
  }

  public String expired(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("iat", clock.epochSecond() - TTL - 10);
    c.put("exp", clock.epochSecond() - 10);
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  /** exp == now : déjà expiré (now >= exp, tolérance 0). */
  public String expiringNow(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("exp", clock.epochSecond());
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  /** Expiré ET signature fausse : Lexik répond « Expired JWT Token » (§8.1). */
  public String expiredWithBadSignature(String username) {
    return tamperSignature(expired(username));
  }

  public String badSignature(String username) {
    return tamperSignature(valid(username));
  }

  public String algNone(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "none");
    return encode(h) + "." + encode(symfonyClaims(username)) + ".";
  }

  /** Confusion d'algorithme : HS256 signé avec le PEM public comme secret. */
  public String hs256WithPublicKey(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "HS256");
    String signingInput = encode(h) + "." + encode(symfonyClaims(username));
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              keys.publicPemText().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      return signingInput
          + "."
          + B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String signedByOtherKey(String username) {
    return rs256(lexikHeader(), symfonyClaims(username), TestKeys.newKeyPair().getPrivate());
  }

  /** RS512 signé par la bonne clé : seul RS256 est accepté. */
  public String rs512(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "RS512");
    return sign(h, symfonyClaims(username), keys.privateKey(), "SHA512withRSA");
  }

  public String withoutExp(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.remove("exp");
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  public String expAsString(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("exp", String.valueOf(clock.epochSecond() + TTL));
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  public String futureIat(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("iat", clock.epochSecond() + 60);
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  public String futureNbf(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("nbf", clock.epochSecond() + 60);
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  public String withoutUsername() {
    Map<String, Object> c = symfonyClaims("ignored@exemple.test");
    c.remove("username");
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  /** Jeton identity-service attendu (uid ajouté). */
  public String withUid(String username, int uid) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("uid", uid);
    return rs256(lexikHeader(), c, keys.privateKey());
  }

  public String rs256(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
    return sign(header, claims, key, "SHA256withRSA");
  }

  private static String sign(
      Map<String, Object> header, Map<String, Object> claims, PrivateKey key, String algorithm) {
    String signingInput = encode(header) + "." + encode(claims);
    try {
      Signature s = Signature.getInstance(algorithm);
      s.initSign(key);
      s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + B64.encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String encode(Map<String, Object> part) {
    return B64.encodeToString(Json.write(part).getBytes(StandardCharsets.UTF_8));
  }

  private static String tamperSignature(String token) {
    int dot = token.lastIndexOf('.');
    byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
    sig[sig.length / 2] ^= 0x01;
    return token.substring(0, dot + 1) + B64.encodeToString(sig);
  }

  /** Décode une partie (0 = en-tête, 1 = charge) en texte JSON. */
  public static String decodePart(String token, int index) {
    return new String(
        Base64.getUrlDecoder().decode(token.split("\\.")[index]), StandardCharsets.UTF_8);
  }
}
