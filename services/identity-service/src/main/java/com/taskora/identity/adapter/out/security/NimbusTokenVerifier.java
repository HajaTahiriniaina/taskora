package com.taskora.identity.adapter.out.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.VerifiedToken;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.TokenRejection;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Validation au format Lexik, précédence du §8.1 (A1) : jeton illisible → INVALID ; now ≥ exp →
 * EXPIRED (l'emporte sur tout le reste, signature comprise) ; exp absent, iat futur, alg ≠ RS256,
 * signature fausse, nbf futur → INVALID ; username absent → MISSING_USERNAME. Tolérance 0 s.
 */
public class NimbusTokenVerifier implements TokenVerifierPort {
  private final RSASSAVerifier verifier;
  private final Clock clock;

  public NimbusTokenVerifier(RSAPublicKey key, Clock clock) {
    this.verifier = new RSASSAVerifier(key);
    this.clock = clock;
  }

  @Override
  public VerifiedToken verify(String token) {
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw rejected(TokenRejection.INVALID);
    }
    final Map<String, Object> header = json(parts[0]);
    Map<String, Object> claims = json(parts[1]);
    long now = clock.instant().getEpochSecond();

    Double exp = numericDate(claims, "exp");
    if (exp != null && now >= exp) {
      throw rejected(TokenRejection.EXPIRED);
    }
    if (exp == null) {
      throw rejected(TokenRejection.INVALID);
    }
    Double iat = numericDate(claims, "iat");
    if (iat != null && iat > now) {
      throw rejected(TokenRejection.INVALID);
    }
    // alg vérifié avant toute opération cryptographique : seul RS256 est accepté.
    if (!JWSAlgorithm.RS256.getName().equals(header.get("alg"))) {
      throw rejected(TokenRejection.INVALID);
    }
    if (!signatureValid(token)) {
      throw rejected(TokenRejection.INVALID);
    }
    Double nbf = numericDate(claims, "nbf");
    if (nbf != null && nbf > now) {
      throw rejected(TokenRejection.INVALID);
    }
    Object username = claims.get("username");
    if (username == null) {
      throw rejected(TokenRejection.MISSING_USERNAME);
    }
    return new VerifiedToken(String.valueOf(username), Collections.unmodifiableMap(claims));
  }

  private boolean signatureValid(String token) {
    try {
      return SignedJWT.parse(token).verify(verifier);
    } catch (Exception e) {
      return false;
    }
  }

  private static Map<String, Object> json(String part) {
    try {
      return JSONObjectUtils.parse(new Base64URL(part).decodeToString());
    } catch (Exception e) {
      throw rejected(TokenRejection.INVALID);
    }
  }

  /** Date numérique (nombre ou chaîne numérique, comme lcobucci) ; absente → null. */
  private static @Nullable Double numericDate(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.valueOf(value.toString().trim());
    } catch (NumberFormatException e) {
      throw rejected(TokenRejection.INVALID);
    }
  }

  private static TokenRejectedException rejected(TokenRejection reason) {
    return new TokenRejectedException(reason);
  }
}
