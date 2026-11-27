package com.taskora.board.config;

import com.taskora.board.adapter.in.cli.BoardCliRunner;
import com.taskora.board.adapter.in.scheduling.OutboxRelayScheduler;
import com.taskora.board.adapter.in.web.RoutingMarkerFilter;
import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Adapters entrants hors contrôleurs : relais planifié, commandes, marqueur de routage. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(BoardProperties.class)
public class AdapterInConfig {

  @Bean
  public OutboxRelayScheduler outboxRelayScheduler(
      DeliverPendingMessagesUseCase relay, BoardProperties properties) {
    return new OutboxRelayScheduler(relay, properties.outbox().relay().enabled());
  }

  @Bean
  public BoardCliRunner boardCliRunner(
      VerifyBoardInvariantsUseCase verify, DeliverPendingMessagesUseCase relay) {
    return new BoardCliRunner(verify, relay);
  }

  @Bean
  @ConditionalOnWebApplication
  public RoutingMarkerFilter routingMarkerFilter(BoardProperties properties) {
    return new RoutingMarkerFilter(properties.routing().marker());
  }
}
