package com.taskora.identity.config;

import com.taskora.identity.adapter.in.web.security.LexikAuthenticationEntryPoint;
import com.taskora.identity.adapter.in.web.security.LexikCompatibleJwtFilter;
import com.taskora.identity.adapter.in.web.security.LexikTokenExtractor;
import com.taskora.identity.application.port.in.ResolveAuthenticatedUserUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * API sans état : pas de session ni de CSRF (jeton porté par l'en-tête Authorization, aucun cookie),
 * /api/auth/** public, tout le reste authentifié par le filtre compatible Lexik (A1, A4).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  public SecurityFilterChain apiSecurity(HttpSecurity http, ResolveAuthenticatedUserUseCase resolver)
      throws Exception {
    LexikAuthenticationEntryPoint entryPoint = new LexikAuthenticationEntryPoint();
    // Filtre instancié ici (pas un bean) : il ne doit pas être enregistré une seconde fois par Boot.
    LexikCompatibleJwtFilter jwtFilter =
        new LexikCompatibleJwtFilter(resolver, new LexikTokenExtractor(), entryPoint);
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .anonymous(a -> {})
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/auth/**", "/error", "/actuator/health/**", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
