package com.taskora.identity.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * Propriétés identity.* (A13) : toutes alimentées par des variables d'environnement.
 *
 * <p>La liaison échoue au démarrage si INTERNAL_API_SECRET est absent ou blanc (A18, SC-IDENT-036).
 */
@ConfigurationProperties("identity")
public record IdentityProperties(
    Jwt jwt, Legacy legacy, Symfony symfony, Password password, Outbox outbox) {

  static final String MISSING_INTERNAL_SECRET = "INTERNAL_API_SECRET doit être défini et non vide.";

  /**
   * Valide les propriétés liées.
   *
   * @throws IllegalStateException si la section identity.symfony est absente (donc sans secret)
   */
  public IdentityProperties {
    if (symfony == null) {
      throw new IllegalStateException(MISSING_INTERNAL_SECRET);
    }
  }

  /**
   * Paire de clés Lexik (volume jwt_keys en lecture seule) et durée de vie des jetons.
   *
   * <p>La passphrase reste une {@code String} liée par Spring (m8, accepté) : le {@code char[]} qui
   * en est tiré pour BouncyCastle est effacé après le chargement de la clé ; elle n'apparaît jamais
   * dans {@link #toString()}.
   */
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

  /**
   * Endpoint Symfony transitoire /internal/invitations/claim et secret partagé.
   *
   * <p>Un secret absent, vide ou blanc ferait échouer définitivement chaque claim (401) : le
   * service refuse donc de démarrer, avec un message qui nomme la variable sans jamais citer de
   * valeur.
   */
  public record Symfony(String internalUrl, @Nullable String internalSecret) {

    /**
     * Valide le secret partagé.
     *
     * @throws IllegalStateException si le secret est absent, vide ou composé d'espaces
     */
    public Symfony {
      if (internalSecret == null || internalSecret.isBlank()) {
        throw new IllegalStateException(MISSING_INTERNAL_SECRET);
      }
    }

    @Override
    public String toString() {
      return "Symfony[internalUrl=" + internalUrl + "]";
    }
  }

  /**
   * Hachage des mots de passe (A2, B1).
   *
   * <ul>
   *   <li>{@code identity.password.bcrypt-cost} ({@code IDENTITY_BCRYPT_COST}) : coût bcrypt des
   *       nouveaux hashes, 13 comme Symfony (4 en test) ;
   *   <li>{@code identity.password.bcrypt-engine} ({@code IDENTITY_BCRYPT_ENGINE}) : {@code native}
   *       (défaut) = {@code crypt_rn} de libxcrypt ({@code libcrypt.so.1}) via FFM, à parité de
   *       latence avec PHP ; {@code java} = Spring Security BCrypt en Java pur (≈ 35 % plus lent),
   *       réservé au développement hors Linux (macOS n'a pas {@code libcrypt.so.1}). Moteur {@code
   *       native} choisi mais bibliothèque introuvable → le service refuse de démarrer. La JVM doit
   *       être lancée avec {@code --enable-native-access=ALL-UNNAMED}.
   * </ul>
   */
  public record Password(int bcryptCost, @DefaultValue("native") BcryptEngine bcryptEngine) {

    /** Moteur bcrypt (B1). */
    public enum BcryptEngine {
      /** Moteur natif : libxcrypt via FFM, en production (défaut). */
      NATIVE,
      /** Moteur Java pur : Spring Security BCrypt, pour le développement hors Linux. */
      JAVA
    }
  }

  /** Relais de l'outbox. */
  public record Outbox(Relay relay) {
    /** Relais planifié activable. */
    public record Relay(boolean enabled) {}
  }
}
