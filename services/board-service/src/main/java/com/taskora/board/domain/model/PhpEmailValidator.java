package com.taskora.board.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Équivalent de filter_var($e, FILTER_VALIDATE_EMAIL) sans drapeau : port fidèle de l'expression de
 * php_filter_validate_email (ext/filter/logical_filters.c, sans FILTER_FLAG_EMAIL_UNICODE) et de la
 * limite de 320 octets. PCRE y travaille sur des octets (pas de modificateur u), avec /i et /D : la
 * chaîne est donc confrontée octet par octet (ISO-8859-1), « . » n'exclut que \n et la fin est \z.
 *
 * <p>Les répétitions non bornées de l'expression PHP sont évaluées par un parcours linéaire (pile
 * constante, quelle que soit la taille de l'entrée) ; seules les adresses IP littérales, de
 * longueur bornée par leur forme, restent confrontées à des expressions sans répétition ouverte de
 * groupe. Correspondance avec l'expression d'origine :
 *
 * <ul>
 *   <li>{@code (?!COUNTED{255,})} et {@code (?!COUNTED{65,}@)} : {@link #countedLimitsHold} ;
 *   <li>{@code WORD(\.WORD)*@} : {@link #localPartEnd} (analyse déterministe : atome maximal ou
 *       chaîne entre guillemets fermée au premier guillemet non échappé) ;
 *   <li>nom d'hôte : {@link #isHostname} ; littéral {@code [..]} : {@link #isLiteral}.
 * </ul>
 */
final class PhpEmailValidator {
  private static final int MAX_BYTES = 320;
  private static final int MAX_COUNTED = 255;
  private static final int MAX_COUNTED_LOCAL = 65;
  private static final int MAX_LABEL = 63;
  private static final int IPV6_FULL_PAIRS = 7;
  private static final int IPV6_V4_PAIRS = 5;
  private static final String IPV6_PREFIX = "IPv6:";

  private static final String HEX = "[a-f0-9]{1,4}";
  private static final String IPV4_PART =
      "(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))";
  private static final String IPV4 = IPV4_PART + "(?:\\." + IPV4_PART + "){3}";
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES;

  /** Formes sans contrôle préalable : IPv6 complète, IPv6 complète + IPv4, IPv4 seule. */
  private static final Pattern LITERAL_PLAIN =
      Pattern.compile(
          "(?:IPv6:"
              + HEX
              + "(?::"
              + HEX
              + "){7})|(?:IPv6:"
              + HEX
              + "(?::"
              + HEX
              + "){5}:"
              + IPV4
              + ")|(?:"
              + IPV4
              + ")",
          FLAGS);

  /** IPv6 compressée ; valable seulement s'il y a moins de 7 paires hexa + « : » ou « ] ». */
  private static final Pattern LITERAL_IPV6_COMPRESSED =
      Pattern.compile(
          "IPv6:(?:" + HEX + "(?::" + HEX + "){0,5})?::(?:" + HEX + "(?::" + HEX + "){0,5})?",
          FLAGS);

  /** IPv6 compressée + IPv4 ; valable seulement s'il y a moins de 5 paires hexa + « : ». */
  private static final Pattern LITERAL_IPV6_V4_COMPRESSED =
      Pattern.compile(
          "IPv6:(?:"
              + HEX
              + "(?::"
              + HEX
              + "){0,3})?::(?:"
              + HEX
              + "(?::"
              + HEX
              + "){0,3}:)?"
              + IPV4,
          FLAGS);

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
    return matches(new String(bytes, StandardCharsets.ISO_8859_1));
  }

  /**
   * Confronte la chaîne (un caractère par octet) à l'expression de PHP, sans limite de longueur.
   *
   * @param s octets de l'adresse, lus en ISO-8859-1
   * @return {@code true} si l'expression de PHP reconnaît la chaîne entière
   */
  static boolean matches(String s) {
    if (!countedLimitsHold(s)) {
      return false;
    }
    int at = localPartEnd(s);
    if (at < 0) {
      return false;
    }
    String domain = s.substring(at + 1);
    return isHostname(domain) || isLiteral(domain);
  }

  /**
   * Lookaheads {@code (?!C{255,})(?!C{65,}@)} avec C = {@code "?(\\[\x00-\x7E]|[^\\"])"?} :
   * programmation dynamique du nombre maximal d'unités C pavant exactement chaque préfixe.
   */
  private static boolean countedLimitsHold(String s) {
    int n = s.length();
    int[] best = new int[n + 5];
    Arrays.fill(best, -1);
    best[0] = 0;
    int reach = 0;
    for (int p = 0; p <= n && p <= reach; p++) {
      int units = best[p];
      if (units < 0) {
        continue;
      }
      if (units >= MAX_COUNTED || (units >= MAX_COUNTED_LOCAL && p < n && s.charAt(p) == '@')) {
        return false;
      }
      reach = Math.max(reach, extendUnit(s, p, p, units + 1, best));
      if (p < n && s.charAt(p) == '"') {
        reach = Math.max(reach, extendUnit(s, p, p + 1, units + 1, best));
      }
    }
    return true;
  }

  /** Enregistre les fins possibles d'une unité dont le cœur commence en {@code core}. */
  private static int extendUnit(String s, int start, int core, int units, int[] best) {
    int n = s.length();
    if (core >= n || s.charAt(core) == '"') {
      return start;
    }
    int end;
    if (s.charAt(core) == '\\') {
      if (core + 1 >= n || s.charAt(core + 1) > 0x7E) {
        return start;
      }
      end = core + 2;
    } else {
      end = core + 1;
    }
    best[end] = Math.max(best[end], units);
    if (end < n && s.charAt(end) == '"') {
      best[end + 1] = Math.max(best[end + 1], units);
      return end + 1;
    }
    return end;
  }

  /**
   * Partie locale {@code WORD(\.WORD)*} suivie de « @ ».
   *
   * @return position du « @ », ou -1
   */
  private static int localPartEnd(String s) {
    int pos = wordEnd(s, 0);
    while (pos >= 0 && pos < s.length() && s.charAt(pos) == '.') {
      pos = wordEnd(s, pos + 1);
    }
    return pos >= 0 && pos < s.length() && s.charAt(pos) == '@' ? pos : -1;
  }

  /** Fin d'un mot (atome maximal ou chaîne entre guillemets) commençant en {@code start}, ou -1. */
  private static int wordEnd(String s, int start) {
    int n = s.length();
    if (start >= n) {
      return -1;
    }
    if (s.charAt(start) == '"') {
      int i = start + 1;
      while (i < n) {
        char c = s.charAt(i);
        if (c == '"') {
          return i + 1;
        }
        if (c == '\\') {
          if (i + 1 >= n || s.charAt(i + 1) > 0x7F) {
            return -1;
          }
          i += 2;
        } else if (isQuotedChar(c)) {
          i++;
        } else {
          return -1;
        }
      }
      return -1;
    }
    int i = start;
    while (i < n && isAtomChar(s.charAt(i))) {
      i++;
    }
    return i > start ? i : -1;
  }

  /** {@code [\x21\x23-\x27\x2A\x2B\x2D\x2F-\x39\x3D\x3F\x5E-\x7E]} sous /i. */
  private static boolean isAtomChar(char c) {
    return c == 0x21
        || (c >= 0x23 && c <= 0x27)
        || c == 0x2A
        || c == 0x2B
        || c == 0x2D
        || (c >= 0x2F && c <= 0x39)
        || c == 0x3D
        || c == 0x3F
        || (c >= 'A' && c <= 'Z')
        || (c >= 0x5E && c <= 0x7E);
  }

  /** {@code [\x01-\x08\x0B\x0C\x0E-\x1F\x21\x23-\x5B\x5D-\x7F]}. */
  private static boolean isQuotedChar(char c) {
    return (c >= 0x01 && c <= 0x08)
        || c == 0x0B
        || c == 0x0C
        || (c >= 0x0E && c <= 0x1F)
        || c == 0x21
        || (c >= 0x23 && c <= 0x5B)
        || (c >= 0x5D && c <= 0x7F);
  }

  /**
   * Nom d'hôte : au moins deux labels {@code [a-z0-9]+(-+[a-z0-9]+)*} séparés par « . », aucun de
   * 64 caractères ou plus, le dernier commençant par une lettre (ce qui couvre aussi la forme
   * {@code xn--}).
   */
  private static boolean isHostname(String domain) {
    int labels = 0;
    int start = 0;
    int n = domain.length();
    for (int i = 0; i <= n; i++) {
      if (i == n || domain.charAt(i) == '.') {
        if (!isLabel(domain, start, i)) {
          return false;
        }
        labels++;
        if (i < n) {
          start = i + 1;
        }
      }
    }
    return labels >= 2 && isLetter(domain.charAt(start));
  }

  private static boolean isLabel(String domain, int from, int to) {
    if (to <= from || to - from > MAX_LABEL) {
      return false;
    }
    if (!isAlnum(domain.charAt(from)) || !isAlnum(domain.charAt(to - 1))) {
      return false;
    }
    for (int i = from; i < to; i++) {
      char c = domain.charAt(i);
      if (c != '-' && !isAlnum(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isAlnum(char c) {
    return isLetter(c) || (c >= '0' && c <= '9');
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /** Littéral {@code [IPv6:..]} ou {@code [IPv4]}, en fin de chaîne. */
  private static boolean isLiteral(String domain) {
    int n = domain.length();
    if (n < 2 || domain.charAt(0) != '[' || domain.charAt(n - 1) != ']') {
      return false;
    }
    String body = domain.substring(1, n - 1);
    if (LITERAL_PLAIN.matcher(body).matches()) {
      return true;
    }
    if (!body.regionMatches(true, 0, IPV6_PREFIX, 0, IPV6_PREFIX.length())) {
      return false;
    }
    // Les lookaheads PHP portent sur la suite de la chaîne, « ] » final compris.
    String rest = domain.substring(1 + IPV6_PREFIX.length());
    return (LITERAL_IPV6_COMPRESSED.matcher(body).matches()
            && hexPairs(rest, true) < IPV6_FULL_PAIRS)
        || (LITERAL_IPV6_V4_COMPRESSED.matcher(body).matches()
            && hexPairs(rest, false) < IPV6_V4_PAIRS);
  }

  /** Nombre de paires {@code [a-f0-9]:} (ou {@code [a-f0-9]]} si {@code bracket}). */
  private static int hexPairs(String rest, boolean bracket) {
    int count = 0;
    for (int i = 0; i + 1 < rest.length(); i++) {
      char next = rest.charAt(i + 1);
      if (isHex(rest.charAt(i)) && (next == ':' || (bracket && next == ']'))) {
        count++;
      }
    }
    return count;
  }
}
