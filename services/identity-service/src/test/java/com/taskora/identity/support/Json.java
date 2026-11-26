package com.taskora.identity.support;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Sérialisation JSON minimale pour construire des corps et des jetons exacts dans les tests. */
public final class Json {
  private Json() {}

  public static String write(Object o) {
    StringBuilder sb = new StringBuilder();
    append(sb, o);
    return sb.toString();
  }

  private static void append(StringBuilder sb, Object o) {
    switch (o) {
      case null -> sb.append("null");
      case String s -> appendString(sb, s);
      case Number n -> sb.append(n);
      case Boolean b -> sb.append(b);
      case Map<?, ?> m -> {
        sb.append('{');
        Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
        while (it.hasNext()) {
          Map.Entry<?, ?> e = it.next();
          appendString(sb, String.valueOf(e.getKey()));
          sb.append(':');
          append(sb, e.getValue());
          if (it.hasNext()) {
            sb.append(',');
          }
        }
        sb.append('}');
      }
      case List<?> l -> {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          append(sb, l.get(i));
        }
        sb.append(']');
      }
      default -> throw new IllegalArgumentException("type non géré : " + o.getClass());
    }
  }

  private static void appendString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
