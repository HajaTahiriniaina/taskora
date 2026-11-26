package com.taskora.identity.domain.model;

import java.util.List;
import java.util.random.RandomGenerator;

/** Couleur d'avatar : palette à la création, puis exactement #RRGGBB (E18). */
public record AvatarColor(String value) {
  public static final List<String> PALETTE =
      List.of(
          "#6366f1", "#ec4899", "#14b8a6", "#f59e0b", "#8b5cf6", "#ef4444", "#0ea5e9", "#22c55e");

  public static AvatarColor random(RandomGenerator random) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * E18 : la valeur doit correspondre à \A#[0-9a-fA-F]{6}\z, sinon ValidationException("Couleur
   * d'avatar invalide.").
   */
  public static AvatarColor parse(String raw) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
