package com.taskora.identity.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ligne de identity.user_account (V1__create_user_account.sql). */
@Entity
@Table(name = "user_account")
public class UserAccountEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "email", nullable = false, length = 180)
  private String email;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "avatar_color", nullable = false, length = 20)
  private String avatarColor;

  /** JSON (tableau de chaînes) ; MariaDB stocke JSON en LONGTEXT. */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "roles", nullable = false)
  private String roles;

  /** Heure locale Europe/Paris, comme Symfony. */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** UTC, propre à identity. */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  protected UserAccountEntity() {}

  UserAccountEntity(
      String email,
      String name,
      String passwordHash,
      String avatarColor,
      String roles,
      LocalDateTime createdAt) {
    this.email = email;
    this.name = name;
    this.passwordHash = passwordHash;
    this.avatarColor = avatarColor;
    this.roles = roles;
    this.createdAt = createdAt;
  }

  Integer getId() {
    return id;
  }

  String getEmail() {
    return email;
  }

  String getName() {
    return name;
  }

  String getPasswordHash() {
    return passwordHash;
  }

  String getAvatarColor() {
    return avatarColor;
  }

  String getRoles() {
    return roles;
  }

  LocalDateTime getCreatedAt() {
    return createdAt;
  }

  void applyProfile(String name, String passwordHash, String avatarColor, LocalDateTime at) {
    this.name = name;
    this.passwordHash = passwordHash;
    this.avatarColor = avatarColor;
    this.updatedAt = at;
  }
}
