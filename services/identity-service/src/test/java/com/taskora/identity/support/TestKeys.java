package com.taskora.identity.support;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OutputEncryptor;

/**
 * Paire RSA 2048 générée à l'exécution et écrite en PEM PKCS#8 chiffré (PBES2, comme Lexik). Aucune
 * clé n'est commitée ; la passphrase est aléatoire.
 */
public final class TestKeys {
  /** Chiffrement de la clé privée. */
  public enum Cipher {
    /** DES-EDE3-CBC + PBKDF2-HMAC-SHA256 : format de lexik:jwt:generate-keypair. */
    DES3(PKCS8Generator.DES3_CBC),
    AES256(PKCS8Generator.AES_256_CBC);

    private final ASN1ObjectIdentifier oid;

    Cipher(ASN1ObjectIdentifier oid) {
      this.oid = oid;
    }
  }

  private static TestKeys shared;

  private final KeyPair keyPair;
  private final Path privatePem;
  private final Path publicPem;
  private final String passphrase;

  private TestKeys(KeyPair keyPair, Path privatePem, Path publicPem, String passphrase) {
    this.keyPair = keyPair;
    this.privatePem = privatePem;
    this.publicPem = publicPem;
    this.passphrase = passphrase;
  }

  /** Paire partagée par les tests d'API (dossier temporaire, supprimé à la sortie de la JVM). */
  public static synchronized TestKeys shared() {
    if (shared == null) {
      try {
        Path dir = Files.createTempDirectory("identity-jwt-");
        dir.toFile().deleteOnExit();
        shared = generate(dir, Cipher.DES3);
        shared.privatePem.toFile().deleteOnExit();
        shared.publicPem.toFile().deleteOnExit();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return shared;
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

  public static TestKeys generate(Path dir, Cipher cipher) {
    KeyPair kp = newKeyPair();
    byte[] raw = new byte[24];
    new SecureRandom().nextBytes(raw);
    String passphrase = HexFormat.of().formatHex(raw);
    try {
      JceOpenSSLPKCS8EncryptorBuilder builder = new JceOpenSSLPKCS8EncryptorBuilder(cipher.oid);
      builder.setProvider(new BouncyCastleProvider());
      builder.setRandom(new SecureRandom());
      builder.setPassword(passphrase.toCharArray());
      builder.setPRF(PKCS8Generator.PRF_HMACSHA256);
      OutputEncryptor encryptor = builder.build();
      Path priv = dir.resolve("private.pem");
      Path pub = dir.resolve("public.pem");
      Files.writeString(priv, pem(new JcaPKCS8Generator(kp.getPrivate(), encryptor)));
      Files.writeString(pub, pem(kp.getPublic()));
      return new TestKeys(kp, priv, pub, passphrase);
    } catch (Exception e) {
      throw new IllegalStateException("génération de la paire de test impossible", e);
    }
  }

  private static String pem(Object o) throws IOException {
    StringWriter sw = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
      w.writeObject(o);
    }
    return sw.toString();
  }

  public RSAPrivateKey privateKey() {
    return (RSAPrivateKey) keyPair.getPrivate();
  }

  public RSAPublicKey publicKey() {
    return (RSAPublicKey) keyPair.getPublic();
  }

  public Path privatePem() {
    return privatePem;
  }

  public Path publicPem() {
    return publicPem;
  }

  public String passphrase() {
    return passphrase;
  }

  /** Contenu du PEM public, utilisé comme secret HMAC dans l'attaque par confusion d'algorithme. */
  public String publicPemText() {
    try {
      return Files.readString(publicPem, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String privatePemText() {
    try {
      return Files.readString(privatePem, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
