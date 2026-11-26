package com.taskora.identity.adapter.out.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.domain.model.User;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Émission au format Lexik (A1) : en-tête {"typ":"JWT","alg":"RS256"} sans kid ; iat, exp (secondes
 * entières), roles, username ; ajout de uid.
 */
public class NimbusTokenIssuer implements TokenIssuerPort {
  private static final JWSHeader HEADER =
      new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();

  private final RSASSASigner signer;
  private final Clock clock;
  private final Duration ttl;

  public NimbusTokenIssuer(LexikKeyLoader keys, Clock clock, Duration ttl) {
    this.signer = new RSASSASigner(keys.privateKey());
    this.clock = clock;
    this.ttl = ttl;
  }

  @Override
  public String issue(User user) {
    long iat = clock.instant().getEpochSecond();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issueTime(new Date(iat * 1000L))
            .expirationTime(new Date((iat + ttl.toSeconds()) * 1000L))
            .claim("roles", user.roles().effective())
            .claim("username", user.email().value())
            .claim("uid", user.id().value())
            .build();
    SignedJWT jwt = new SignedJWT(HEADER, claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("Signature du jeton impossible", e);
    }
    return jwt.serialize();
  }
}
