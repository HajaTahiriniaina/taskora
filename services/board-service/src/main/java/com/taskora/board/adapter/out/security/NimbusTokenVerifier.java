package com.taskora.board.adapter.out.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.taskora.board.application.port.out.TokenVerifierPort;
import com.taskora.board.application.port.out.VerifiedToken;
import com.taskora.board.domain.exception.TokenRejectedException;
import com.taskora.board.domain.exception.TokenRejection;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Validation au format Lexik, reprise d'identity (B-ADR-2 point 2, A1, §8.1) : jeton illisible →
 * INVALID ; {@code now ≥ exp} → EXPIRED (l'emporte sur tout le reste, signature comprise) ; {@code
 * exp} absent ou non numérique, {@code iat} futur, {@code alg ≠ RS256}, signature fausse, {@code
 * nbf} futur → INVALID. Tolérance 0 s. Clé publique seule. La résolution du compte ({@code uid},
 * {@code username}) est faite par le cas d'utilisation.
 */
public class NimbusTokenVerifier implements TokenVerifierPort {
  /**
   * Grammaire de {@code is_numeric} en PHP 8 : espaces ASCII de tête et de fin, signe facultatif,
   * décimal (partie entière ou fractionnaire), exposant facultatif ; rien d'autre.
   */
  private static final Pattern PHP_NUMERIC =
      Pattern.compile(
          "^[ \\t\\n\\r\\x0B\\f]*"
              + "[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?"
              + "[ \\t\\n\\r\\x0B\\f]*$");

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
    return new VerifiedToken(uid(claims.get("uid")), username == null ? null : username.toString());
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

  /**
   * Date numérique comme {@code lcobucci/jwt} ({@code Parser::convertDate}) : nombre JSON, ou
   * chaîne conforme à {@code is_numeric} de PHP 8 ({@link #PHP_NUMERIC}) ; {@code NaN}, {@code
   * Infinity}, hexadécimal, suffixe ({@code d}, {@code f}) et valeur non finie refusés ; absente →
   * null (A1 point 3, §8.1, E14).
   */
  private static @Nullable Double numericDate(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    if (value == null) {
      return null;
    }
    double date;
    if (value instanceof Number number) {
      date = number.doubleValue();
    } else if (value instanceof String text && PHP_NUMERIC.matcher(text).matches()) {
      date = Double.parseDouble(text.strip());
    } else {
      throw rejected(TokenRejection.INVALID);
    }
    if (!Double.isFinite(date)) {
      throw rejected(TokenRejection.INVALID);
    }
    return date;
  }

  /** {@code uid} d'identity : entier positif, sinon jeton invalide ; absent (Symfony) → null. */
  private static @Nullable Integer uid(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n
        && n.doubleValue() == Math.rint(n.doubleValue())
        && n.longValue() >= 1
        && n.longValue() <= Integer.MAX_VALUE) {
      return n.intValue();
    }
    throw rejected(TokenRejection.INVALID);
  }

  private static TokenRejectedException rejected(TokenRejection reason) {
    return new TokenRejectedException(reason);
  }
}
