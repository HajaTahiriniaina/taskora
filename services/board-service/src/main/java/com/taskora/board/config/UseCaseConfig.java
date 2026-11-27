package com.taskora.board.config;

import com.taskora.board.adapter.out.security.NimbusTokenVerifier;
import com.taskora.board.adapter.out.security.PublicKeyLoader;
import com.taskora.board.application.port.out.BoardAccessQueryPort;
import com.taskora.board.application.port.out.BoardReadModelPort;
import com.taskora.board.application.port.out.BoardRepositoryPort;
import com.taskora.board.application.port.out.InvariantsQueryPort;
import com.taskora.board.application.port.out.InvitationRepositoryPort;
import com.taskora.board.application.port.out.MembershipRepositoryPort;
import com.taskora.board.application.port.out.NotificationDeliveryPort;
import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.RequestBodyParser;
import com.taskora.board.application.port.out.TokenVerifierPort;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.application.service.AuthenticatedUserResolver;
import com.taskora.board.application.service.BoardCommandService;
import com.taskora.board.application.service.BoardQueryService;
import com.taskora.board.application.service.InvariantVerificationService;
import com.taskora.board.application.service.InvitationClaimService;
import com.taskora.board.application.service.MembershipService;
import com.taskora.board.application.service.OutboxDeliveryService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assemblage des cas d'utilisation (Java pur, sans annotation Spring dans application). */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfig {

  @Bean
  public NimbusTokenVerifier tokenVerifier(BoardProperties properties, Clock clock) {
    return new NimbusTokenVerifier(
        PublicKeyLoader.load(Path.of(properties.jwt().publicKeyPath())), clock);
  }

  @Bean
  public AuthenticatedUserResolver authenticatedUserResolver(
      TokenVerifierPort tokens, UserDirectoryPort users) {
    return new AuthenticatedUserResolver(tokens, users);
  }

  @Bean
  public BoardCommandService boardCommandService(
      UnitOfWorkPort unitOfWork,
      BoardAccessQueryPort access,
      BoardRepositoryPort boards,
      MembershipRepositoryPort memberships,
      BoardReadModelPort reads,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    return new BoardCommandService(
        unitOfWork, access, boards, memberships, reads, outbox, parser, clock);
  }

  @Bean
  public BoardQueryService boardQueryService(
      UnitOfWorkPort unitOfWork, BoardAccessQueryPort access, BoardReadModelPort reads) {
    return new BoardQueryService(unitOfWork, access, reads);
  }

  @Bean
  public MembershipService membershipService(
      UnitOfWorkPort unitOfWork,
      BoardAccessQueryPort access,
      BoardRepositoryPort boards,
      MembershipRepositoryPort memberships,
      InvitationRepositoryPort invitations,
      UserDirectoryPort users,
      BoardReadModelPort reads,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    return new MembershipService(
        unitOfWork, access, boards, memberships, invitations, users, reads, outbox, parser, clock);
  }

  @Bean
  public InvitationClaimService invitationClaimService(
      UnitOfWorkPort unitOfWork,
      InvitationRepositoryPort invitations,
      MembershipRepositoryPort memberships,
      BoardRepositoryPort boards,
      UserDirectoryPort users,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    return new InvitationClaimService(
        unitOfWork, invitations, memberships, boards, users, outbox, parser, clock);
  }

  @Bean
  public OutboxDeliveryService outboxDeliveryService(
      OutboxPort outbox,
      NotificationDeliveryPort delivery,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    return new OutboxDeliveryService(outbox, delivery, unitOfWork, clock);
  }

  @Bean
  public InvariantVerificationService invariantVerificationService(
      InvariantsQueryPort invariants, UnitOfWorkPort unitOfWork) {
    return new InvariantVerificationService(invariants, unitOfWork);
  }
}
