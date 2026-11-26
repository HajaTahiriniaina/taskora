package com.taskora.identity.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Équivalent de filter_var($e, FILTER_VALIDATE_EMAIL) sans drapeau : port fidèle de l'expression de
 * php_filter_validate_email (ext/filter/logical_filters.c, sans FILTER_FLAG_EMAIL_UNICODE) et de la
 * limite de 320 octets. PCRE y travaille sur des octets (pas de modificateur u), avec /i et /D : la
 * chaîne est donc confrontée octet par octet (ISO-8859-1), « . » n'exclut que \n et la fin est \z.
 */
public final class PhpEmailValidator {
  private static final int MAX_BYTES = 320;

  private static final String ATOM =
      "[\\x21\\x23-\\x27\\x2A\\x2B\\x2D\\x2F-\\x39\\x3D\\x3F\\x5E-\\x7E]+";
  private static final String QUOTED_CHAR =
      "[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x21\\x23-\\x5B\\x5D-\\x7F]";
  private static final String QUOTED = "\\x22(?:" + QUOTED_CHAR + "|(?:\\x5C[\\x00-\\x7F]))*\\x22";
  private static final String WORD = "(?:(?:" + ATOM + ")|(?:" + QUOTED + "))";
  // Sonar S5998 (récursion des répétitions alternées) : l'entrée est bornée à 320 octets par
  // isValid avant toute confrontation à l'expression, ce qui borne la pile.
  private static final String COUNTED =
      "(?:(?:\\x22?\\x5C[\\x00-\\x7E]\\x22?)|(?:\\x22?[^\\x5C\\x22]\\x22?))";
  private static final String HOSTNAME =
      "(?:(?!.*[^.]{64,})(?:(?:(?:xn--)?[a-z0-9]+(?:-+[a-z0-9]+)*\\.){1,126}){1,}"
          + "(?:(?:[a-z][a-z0-9]*)|(?:(?:xn--)[a-z0-9]+))(?:-+[a-z0-9]+)*)";
  private static final String IPV4 =
      "(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))"
          + "(?:\\.(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))){3}";
  private static final String IPV6_FULL =
      "(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){7})"
          + "|(?:(?!(?:.*[a-f0-9][:\\]]){7,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?::"
          + "(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?)))";
  private static final String IPV6_V4 =
      "(?:(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){5}:)"
          + "|(?:(?!(?:.*[a-f0-9]:){5,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3})?::"
          + "(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3}:)?)))?"
          + IPV4
          + ")";
  private static final String LITERAL = "(?:\\[(?:" + IPV6_FULL + "|" + IPV6_V4 + ")\\])";

  private static final Pattern EMAIL =
      Pattern.compile(
          "\\A(?!"
              + COUNTED
              + "{255,})(?!"
              + COUNTED
              + "{65,}@)"
              + WORD
              + "(?:\\."
              + WORD
              + ")*@(?:"
              + HOSTNAME
              + "|"
              + LITERAL
              + ")\\z",
          Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);

  private PhpEmailValidator() {}

  /**
   * Indique si filter_var accepterait l'adresse.
   *
   * @param email adresse déjà trimée, tronquée et en minuscules
   * @return {@code true} si l'adresse est valide ; {@code false} au-delà de 320 octets
   */
  public static boolean isValid(String email) {
    byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) {
      return false;
    }
    return EMAIL.matcher(new String(bytes, StandardCharsets.ISO_8859_1)).matches();
  }
}
