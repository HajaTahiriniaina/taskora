package com.taskora.identity.config;

import com.taskora.identity.adapter.in.cli.LegacyImportRunner;
import com.taskora.identity.adapter.in.scheduling.OutboxRelayScheduler;
import com.taskora.identity.adapter.out.legacy.JdbcLegacyUserProjection;
import com.taskora.identity.adapter.out.legacy.JdbcLegacyUserSource;
import com.taskora.identity.adapter.out.legacy.LegacySchema;
import com.taskora.identity.adapter.out.legacy.SymfonyInvitationClaimClient;
import com.taskora.identity.adapter.out.persistence.JpaOutboxAdapter;
import com.taskora.identity.adapter.out.persistence.JpaUnitOfWork;
import com.taskora.identity.adapter.out.persistence.JpaUserRepositoryAdapter;
import com.taskora.identity.adapter.out.persistence.OutboxJpaRepository;
import com.taskora.identity.adapter.out.persistence.UserAccountJpaRepository;
import com.taskora.identity.adapter.out.security.LexikKeyLoader;
import com.taskora.identity.adapter.out.security.NimbusTokenIssuer;
import com.taskora.identity.adapter.out.security.NimbusTokenVerifier;
import com.taskora.identity.adapter.out.security.SymfonyCompatiblePasswordHasher;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.out.InvitationClaimPort;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.LegacyUserSourcePort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.application.service.AuthenticateService;
import com.taskora.identity.application.service.AuthenticatedUserResolver;
import com.taskora.identity.application.service.LegacyImportService;
import com.taskora.identity.application.service.OutboxDeliveryService;
import com.taskora.identity.application.service.ProfileService;
import com.taskora.identity.application.service.RegisterUserService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** Assemblage hexagonal : adapters de sortie, cas d'utilisation (Java pur), adapters d'entrée. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
@EnableScheduling
public class UseCaseConfig {

  // ------------------------------------------------------------------ adapters de sortie

  @Bean
  public LexikKeyLoader lexikKeyLoader(IdentityProperties properties) {
    IdentityProperties.Jwt jwt = properties.jwt();
    if (isBlank(jwt.privateKeyPath()) || isBlank(jwt.publicKeyPath())) {
      throw new IllegalStateException(
          "JWT_PRIVATE_KEY_PATH et JWT_PUBLIC_KEY_PATH sont obligatoires");
    }
    String passphrase = jwt.passphrase() == null ? "" : jwt.passphrase();
    return new LexikKeyLoader(
        Path.of(jwt.privateKeyPath()), Path.of(jwt.publicKeyPath()), passphrase.toCharArray());
  }

  @Bean
  public PasswordHasherPort passwordHasher(IdentityProperties properties) {
    IdentityProperties.Password password = properties.password();
    return switch (password.bcryptEngine()) {
      case NATIVE -> SymfonyCompatiblePasswordHasher.withNativeEngine(password.bcryptCost());
      case JAVA -> SymfonyCompatiblePasswordHasher.withJavaEngine(password.bcryptCost());
    };
  }

  @Bean
  public TokenIssuerPort tokenIssuer(LexikKeyLoader keys, Clock clock, IdentityProperties p) {
    return new NimbusTokenIssuer(keys, clock, p.jwt().ttl());
  }

  @Bean
  public TokenVerifierPort tokenVerifier(LexikKeyLoader keys, Clock clock) {
    return new NimbusTokenVerifier(keys.publicKey(), clock);
  }

  @Bean
  public UserRepositoryPort userRepository(UserAccountJpaRepository repository, Clock clock) {
    return new JpaUserRepositoryAdapter(repository, clock);
  }

  @Bean
  public OutboxPort outbox(OutboxJpaRepository repository) {
    return new JpaOutboxAdapter(repository);
  }

  @Bean
  public UnitOfWorkPort unitOfWork(PlatformTransactionManager transactionManager) {
    return new JpaUnitOfWork(transactionManager);
  }

  @Bean
  public LegacySchema legacySchema(IdentityProperties properties) {
    return new LegacySchema(properties.legacy().schema());
  }

  @Bean
  public LegacyUserProjectionPort legacyProjection(JdbcClient jdbc, LegacySchema schema) {
    return new JdbcLegacyUserProjection(jdbc, schema);
  }

  @Bean
  public LegacyUserSourcePort legacySource(JdbcClient jdbc, LegacySchema schema) {
    return new JdbcLegacyUserSource(jdbc, schema);
  }

  @Bean
  public InvitationClaimPort invitationClaimClient(IdentityProperties properties) {
    IdentityProperties.Symfony symfony = properties.symfony();
    return new SymfonyInvitationClaimClient(
        symfony.internalUrl(), symfony.internalSecret() == null ? "" : symfony.internalSecret());
  }

  // ------------------------------------------------------------------ cas d'utilisation

  @Bean
  public OutboxDeliveryService outboxDelivery(
      OutboxPort outbox, InvitationClaimPort client, UnitOfWorkPort unitOfWork, Clock clock) {
    return new OutboxDeliveryService(outbox, client, unitOfWork, clock);
  }

  @Bean
  public RegisterUserService registerUser(
      UserRepositoryPort users,
      PasswordHasherPort hasher,
      TokenIssuerPort tokens,
      LegacyUserProjectionPort projection,
      OutboxPort outbox,
      UnitOfWorkPort unitOfWork,
      DeliverPendingMessagesUseCase delivery,
      Clock clock,
      RandomGenerator random,
      IdentityProperties properties) {
    return new RegisterUserService(
        users,
        hasher,
        tokens,
        projection,
        outbox,
        unitOfWork,
        delivery,
        clock,
        ZoneId.of(properties.legacy().zone()),
        random);
  }

  @Bean
  public AuthenticateService authenticate(
      UserRepositoryPort users, PasswordHasherPort hasher, TokenIssuerPort tokens) {
    return new AuthenticateService(users, hasher, tokens);
  }

  @Bean
  public ProfileService profile(
      UserRepositoryPort users,
      PasswordHasherPort hasher,
      LegacyUserProjectionPort projection,
      OutboxPort outbox,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    return new ProfileService(users, hasher, projection, outbox, unitOfWork, clock);
  }

  @Bean
  public AuthenticatedUserResolver authenticatedUserResolver(
      TokenVerifierPort verifier, UserRepositoryPort users) {
    return new AuthenticatedUserResolver(verifier, users);
  }

  @Bean
  public LegacyImportService legacyImport(LegacyUserSourcePort source, UnitOfWorkPort unitOfWork) {
    return new LegacyImportService(source, unitOfWork);
  }

  // ------------------------------------------------------------------ adapters d'entrée

  @Bean
  @ConditionalOnProperty(
      name = "identity.outbox.relay.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public OutboxRelayScheduler outboxRelayScheduler(DeliverPendingMessagesUseCase relay) {
    return new OutboxRelayScheduler(relay);
  }

  @Bean
  public LegacyImportRunner legacyImportRunner(
      ImportLegacyUsersUseCase importer, DeliverPendingMessagesUseCase relay) {
    return new LegacyImportRunner(importer, relay);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
