package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.ValidationException;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Couleur d'avatar : palette à la création, puis exactement #RRGGBB (E18). */
public record AvatarColor(String value) {
  public static final List<String> PALETTE =
      List.of(
          "#6366f1", "#ec4899", "#14b8a6", "#f59e0b", "#8b5cf6", "#ef4444", "#0ea5e9", "#22c55e");

  private static final Pattern HEX = Pattern.compile("\\A#[0-9a-fA-F]{6}\\z");
  static final String INVALID = "Couleur d'avatar invalide.";

  public static AvatarColor random(RandomGenerator random) {
    return new AvatarColor(PALETTE.get(random.nextInt(PALETTE.size())));
  }

  /**
   * E18 : la valeur doit correspondre à \A#[0-9a-fA-F]{6}\z, sinon ValidationException("Couleur
   * d'avatar invalide.").
   */
  public static AvatarColor parse(String raw) {
    if (!HEX.matcher(raw).matches()) {
      throw new ValidationException(INVALID);
    }
    return new AvatarColor(raw);
  }
}
