package com.taskora.board.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés {@code board.*} (B-ADR-12), alimentées par les variables d'environnement.
 *
 * <p>Correspondances :
 *
 * <ul>
 *   <li>{@code board.jwt.public-key-path} ← {@code JWT_PUBLIC_KEY_PATH} (jamais de clé privée ni de
 *       passphrase) ;
 *   <li>{@code board.legacy.schema} ← {@code LEGACY_SCHEMA} ({@code [a-z_]+}), {@code
 *       board.legacy.zone} ← {@code LEGACY_ZONE} ;
 *   <li>{@code board.internal.identity-to-board-secret} ← {@code INTERNAL_SECRET_IDENTITY_TO_BOARD}
 *       et {@code board.internal.board-to-symfony-secret} ← {@code
 *       INTERNAL_SECRET_BOARD_TO_SYMFONY} (U12 : non vides et différents, sinon refus de démarrer)
 *       ;
 *   <li>{@code board.symfony.internal-url} ← {@code SYMFONY_INTERNAL_URL} ;
 *   <li>{@code board.outbox.relay.enabled}, {@code board.routing.marker} ← {@code
 *       BOARD_ROUTING_MARKER}, {@code board.purge.enabled} ← {@code BOARD_PURGE_ENABLED}.
 * </ul>
 *
 * <p>Squelette : la validation (A18, U12) est à écrire par BackCodeur.
 *
 * @param jwt clé publique
 * @param legacy schéma et fuseau historiques
 * @param internal secrets internes par appelant
 * @param symfony URL interne de Symfony
 * @param outbox relais
 * @param routing marqueur de routage
 * @param purge purge planifiée (T5)
 */
@ConfigurationProperties("board")
public record BoardProperties(
    Jwt jwt,
    Legacy legacy,
    Internal internal,
    Symfony symfony,
    Outbox outbox,
    Routing routing,
    Purge purge) {

  /**
   * Clé publique des JWT.
   *
   * @param publicKeyPath chemin de {@code public.pem}
   */
  public record Jwt(String publicKeyPath) {}

  /**
   * Schéma et fuseau historiques.
   *
   * @param schema schéma ({@code taskora})
   * @param zone fuseau ({@code Europe/Paris})
   */
  public record Legacy(String schema, String zone) {}

  /**
   * Secrets internes par appelant (U12) ; jamais dans {@link #toString()}.
   *
   * @param identityToBoardSecret secret accepté sur {@code /internal/**}
   * @param boardToSymfonySecret secret envoyé à Symfony
   */
  public record Internal(String identityToBoardSecret, String boardToSymfonySecret) {}

  /**
   * Symfony sur le réseau interne.
   *
   * @param internalUrl URL de base ({@code http://api:80})
   */
  public record Symfony(String internalUrl) {}

  /**
   * Relais de l'outbox.
   *
   * @param relay relais planifié
   */
  public record Outbox(Relay relay) {
    /**
     * Relais planifié.
     *
     * @param enabled actif
     */
    public record Relay(boolean enabled) {}
  }

  /**
   * Marqueur {@code X-Taskora-Backend: board} (SC-BOARD-022).
   *
   * @param marker actif
   */
  public record Routing(boolean marker) {}

  /**
   * Purge planifiée (T5).
   *
   * @param enabled active
   */
  public record Purge(boolean enabled) {}
}
