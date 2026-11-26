package com.taskora.identity.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/** Propriétés identity.* (A13) : toutes alimentées par des variables d'environnement. */
@ConfigurationProperties("identity")
public record IdentityProperties(
    Jwt jwt, Legacy legacy, Symfony symfony, Password password, Outbox outbox) {

  /** Paire de clés Lexik (volume jwt_keys en lecture seule) et durée de vie des jetons. */
  public record Jwt(
      String privateKeyPath,
      String publicKeyPath,
      String passphrase,
      @DurationUnit(ChronoUnit.SECONDS) Duration ttl) {
    @Override
    public String toString() {
      return "Jwt[privateKeyPath=" + privateKeyPath + ", publicKeyPath=" + publicKeyPath + "]";
    }
  }

  /** Schéma historique (taskora) et fuseau de created_at (Europe/Paris, comme PHP). */
  public record Legacy(String schema, String zone) {}

  /** Endpoint Symfony transitoire /internal/invitations/claim et secret partagé. */
  public record Symfony(String internalUrl, String internalSecret) {
    @Override
    public String toString() {
      return "Symfony[internalUrl=" + internalUrl + "]";
    }
  }

  /** Coût bcrypt (13 comme Symfony, 4 en test). */
  public record Password(int bcryptCost) {}

  /** Relais de l'outbox. */
  public record Outbox(Relay relay) {
    /** Relais planifié activable. */
    public record Relay(boolean enabled) {}
  }
}
