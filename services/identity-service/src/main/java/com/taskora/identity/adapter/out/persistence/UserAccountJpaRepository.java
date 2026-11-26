package com.taskora.identity.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accès Spring Data (non exporté : pas de Spring Data REST, A5). */
public interface UserAccountJpaRepository extends JpaRepository<UserAccountEntity, Integer> {
  /** Égalité SQL simple sur la colonne : la collation utf8mb4_unicode_ci décide (A7). */
  Optional<UserAccountEntity> findByEmail(String email);

  boolean existsByEmail(String email);
}
