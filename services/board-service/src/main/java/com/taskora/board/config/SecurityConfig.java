package com.taskora.board.config;

import com.taskora.board.adapter.in.web.ErrorMessages;
import com.taskora.board.adapter.in.web.security.InternalSecretFilter;
import com.taskora.board.adapter.in.web.security.LexikAuthenticationEntryPoint;
import com.taskora.board.adapter.in.web.security.LexikCompatibleJwtFilter;
import com.taskora.board.adapter.in.web.security.LexikTokenExtractor;
import com.taskora.board.application.port.in.ResolveAuthenticatedUserUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * API sans état : pas de session ni de CSRF (jeton dans l'en-tête Authorization, aucun cookie :
 * même justification qu'A16) ; {@code /api/**} authentifié par le filtre compatible Lexik ; {@code
 * /internal/**} protégé par le secret de l'appelant (U12), jamais par un JWT.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  public SecurityFilterChain apiSecurity(
      HttpSecurity http, ResolveAuthenticatedUserUseCase resolver, BoardProperties properties)
      throws Exception {
    LexikAuthenticationEntryPoint entryPoint = new LexikAuthenticationEntryPoint();
    // Filtres instanciés ici (pas des beans) : Boot ne doit pas les enregistrer une seconde fois.
    LexikCompatibleJwtFilter jwtFilter =
        new LexikCompatibleJwtFilter(resolver, new LexikTokenExtractor(), entryPoint);
    InternalSecretFilter internalFilter =
        new InternalSecretFilter(
            properties.internal().identityToBoardSecret(), ErrorMessages.INTERNAL_DENIED);
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .anonymous(a -> {})
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(
                    "/internal/**", "/error", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .addFilterBefore(internalFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
