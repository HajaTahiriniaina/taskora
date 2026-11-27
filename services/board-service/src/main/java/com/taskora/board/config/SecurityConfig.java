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
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * API sans état : pas de session ni de CSRF (jeton dans l'en-tête Authorization, aucun cookie :
 * même justification qu'A16). Deux chaînes choisies sur le chemin routé (décodé et normalisé, celui
 * du routage MVC ; §12.4 B1) : {@code /internal/**} protégé par le secret de l'appelant (U12),
 * jamais par un JWT, refus par défaut ; le reste ({@code /api/**}) authentifié par le filtre
 * compatible Lexik. Le pare-feu {@code StrictHttpFirewall} par défaut n'est pas assoupli.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain internalSecurity(HttpSecurity http, BoardProperties properties)
      throws Exception {
    // Filtre instancié ici (pas un bean) : Boot ne doit pas l'enregistrer une seconde fois.
    InternalSecretFilter internalFilter =
        new InternalSecretFilter(
            properties.internal().identityToBoardSecret(), ErrorMessages.INTERNAL_DENIED);
    return stateless(http)
        .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher("/internal/**"))
        .anonymous(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalSecretFilter.ROLE))
        .exceptionHandling(e ->
            e.authenticationEntryPoint(
                    (request, response, ex) -> internalFilter.writeDenied(response))
                .accessDeniedHandler(
                    (request, response, ex) -> internalFilter.writeDenied(response)))
        .addFilterBefore(internalFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain apiSecurity(
      HttpSecurity http, ResolveAuthenticatedUserUseCase resolver) throws Exception {
    LexikAuthenticationEntryPoint entryPoint = new LexikAuthenticationEntryPoint();
    // Filtre instancié ici (pas un bean) : Boot ne doit pas l'enregistrer une seconde fois.
    LexikCompatibleJwtFilter jwtFilter =
        new LexikCompatibleJwtFilter(resolver, new LexikTokenExtractor(), entryPoint);
    return stateless(http)
        .anonymous(a -> {})
        .authorizeHttpRequests(auth ->
            auth.requestMatchers("/error", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable);
  }
}
