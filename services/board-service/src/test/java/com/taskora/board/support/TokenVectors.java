package com.taskora.board.support;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;

/**
 * Vecteurs de jetons de SC-BOARD-020 (même table que SC-IDENT-010) : paire RSA 2048 générée à
 * l'exécution, clé privée écrite chiffrée comme Lexik (PKCS#8, DES-EDE3-CBC + PBKDF2-HMAC-SHA256)
 * pour prouver que board-service ne lit que {@code public.pem}. Rien n'est commité ; passphrase
 * aléatoire, jamais transmise à board-service.
 */
public final class TokenVectors {
  public static final long TTL = 604_800L;
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static TokenVectors shared;

  private final KeyPair keyPair;
  private final Path publicPem;
  private final Path privatePem;
  private final String passphrase;
  private final MutableClock clock;

  private TokenVectors(
      KeyPair keyPair, Path publicPem, Path privatePem, String passphrase, MutableClock clock) {
    this.keyPair = keyPair;
    this.publicPem = publicPem;
    this.privatePem = privatePem;
    this.passphrase = passphrase;
    this.clock = clock;
  }

  /** Paire partagée par les IT (dossier temporaire supprimé à la sortie de la JVM). */
  public static synchronized TokenVectors shared() {
    if (shared == null) {
      shared = generate(TestClockConfig.CLOCK);
    }
    return shared;
  }

  public static TokenVectors generate(MutableClock clock) {
    try {
      Path dir = Files.createTempDirectory("board-jwt-");
      dir.toFile().deleteOnExit();
      KeyPair kp = newKeyPair();
      byte[] raw = new byte[24];
      new SecureRandom().nextBytes(raw);
      String passphrase = HexFormat.of().formatHex(raw);
      JceOpenSSLPKCS8EncryptorBuilder builder =
          new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.DES3_CBC);
      builder.setProvider(new BouncyCastleProvider());
      builder.setRandom(new SecureRandom());
      builder.setPassword(passphrase.toCharArray());
      builder.setPRF(PKCS8Generator.PRF_HMACSHA256);
      Path priv = dir.resolve("private.pem");
      Path pub = dir.resolve("public.pem");
      Files.writeString(priv, pem(new JcaPKCS8Generator(kp.getPrivate(), builder.build())));
      Files.writeString(pub, pem(kp.getPublic()));
      priv.toFile().deleteOnExit();
      pub.toFile().deleteOnExit();
      return new TokenVectors(kp, pub, priv, passphrase, clock);
    } catch (Exception e) {
      throw new IllegalStateException("génération de la paire de test impossible", e);
    }
  }

  public static KeyPair newKeyPair() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String pem(Object o) throws IOException {
    StringWriter sw = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
      w.writeObject(o);
    }
    return sw.toString();
  }

  public Path publicPem() {
    return publicPem;
  }

  /** Seulement pour vérifier que board-service ne la reçoit jamais. */
  public Path privatePem() {
    return privatePem;
  }

  public String passphrase() {
    return passphrase;
  }

  public String publicPemText() {
    try {
      return Files.readString(publicPem, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ------------------------------------------------------------------ revendications

  public static Map<String, Object> lexikHeader() {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "RS256");
    return h;
  }

  /** Revendications d'un jeton émis par Symfony (pas de uid). */
  public Map<String, Object> symfonyClaims(String username) {
    Map<String, Object> c = new LinkedHashMap<>();
    long now = clock.epochSecond();
    c.put("iat", now);
    c.put("exp", now + TTL);
    c.put("roles", List.of("ROLE_USER"));
    c.put("username", username);
    return c;
  }

  /** Jeton Symfony valide (sans uid). */
  public String symfony(String username) {
    return rs256(lexikHeader(), symfonyClaims(username), keyPair.getPrivate());
  }

  /** Jeton identity-service valide (uid ajouté). */
  public String identity(String username, int uid) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("uid", uid);
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  /** uid seul (username absent). */
  public String uidOnly(int uid) {
    Map<String, Object> c = symfonyClaims("x");
    c.remove("username");
    c.put("uid", uid);
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  public String withoutUsernameNorUid() {
    Map<String, Object> c = symfonyClaims("x");
    c.remove("username");
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  public String expired(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("iat", clock.epochSecond() - TTL - 10);
    c.put("exp", clock.epochSecond() - 10);
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  /** exp == now : déjà expiré (tolérance 0). */
  public String expiringNow(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("exp", clock.epochSecond());
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  /** Expiré ET signature fausse : « Expired JWT Token » (précédence Lexik). */
  public String expiredWithBadSignature(String username) {
    return tamperSignature(expired(username));
  }

  public String badSignature(String username) {
    return tamperSignature(symfony(username));
  }

  public String algNone(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "none");
    return encode(h) + "." + encode(symfonyClaims(username)) + ".";
  }

  /** Confusion d'algorithme : HS256 signé avec le PEM public comme secret. */
  public String hs256WithPublicKey(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "HS256");
    String signingInput = encode(h) + "." + encode(symfonyClaims(username));
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(publicPemText().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      return signingInput
          + "."
          + B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String signedByOtherKey(String username) {
    return rs256(lexikHeader(), symfonyClaims(username), newKeyPair().getPrivate());
  }

  public String rs512(String username) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("typ", "JWT");
    h.put("alg", "RS512");
    return sign(h, symfonyClaims(username), keyPair.getPrivate(), "SHA512withRSA");
  }

  public String futureIat(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("iat", clock.epochSecond() + 60);
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  public String withoutExp(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.remove("exp");
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  /** exp en chaîne numérique valide (is_numeric PHP) : accepté comme une date (lcobucci). */
  public String expAsString(String username) {
    return withExp(username, String.valueOf(clock.epochSecond() + TTL));
  }

  /** exp en chaîne non numérique : « Invalid JWT Token ». */
  public String expAsNonNumericString(String username) {
    return withExp(username, "demain");
  }

  /** exp « NaN » : refusé par is_numeric, donc « Invalid JWT Token ». */
  public String expAsNaN(String username) {
    return withExp(username, "NaN");
  }

  /** exp {@code "<n>d"} (suffixe de littéral Java) : refusé par is_numeric. */
  public String expAsSuffixedNumber(String username) {
    return withExp(username, (clock.epochSecond() + TTL) + "d");
  }

  /** exp en chaîne numérique passée : « Expired JWT Token ». */
  public String expiredAsString(String username) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("iat", clock.epochSecond() - TTL - 10);
    c.put("exp", String.valueOf(clock.epochSecond() - 10));
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  private String withExp(String username, Object exp) {
    Map<String, Object> c = symfonyClaims(username);
    c.put("exp", exp);
    return rs256(lexikHeader(), c, keyPair.getPrivate());
  }

  public String rs256(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
    return sign(header, claims, key, "SHA256withRSA");
  }

  private static String sign(
      Map<String, Object> header, Map<String, Object> claims, PrivateKey key, String algorithm) {
    String signingInput = encode(header) + "." + encode(claims);
    try {
      Signature s = Signature.getInstance(algorithm);
      s.initSign(key);
      s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + B64.encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String encode(Map<String, Object> part) {
    return B64.encodeToString(Json.write(part).getBytes(StandardCharsets.UTF_8));
  }

  private static String tamperSignature(String token) {
    int dot = token.lastIndexOf('.');
    byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
    sig[sig.length / 2] ^= 0x01;
    return token.substring(0, dot + 1) + B64.encodeToString(sig);
  }
}
