package com.taskora.identity.adapter.out.security;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/**
 * Lecture de la paire de clés Lexik (PKCS#8 chiffré PBES2, A1). Les clés sont lues une fois, à la
 * construction ; la passphrase est effacée après usage. Aucun message d'erreur ne contient la
 * passphrase ni le contenu d'une clé (les causes de BouncyCastle ne sont pas propagées).
 */
public class LexikKeyLoader {
  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;

  public LexikKeyLoader(Path privateKey, Path publicKey, char[] passphrase) {
    try {
      this.privateKey = readPrivateKey(privateKey, passphrase);
      this.publicKey = readPublicKey(publicKey);
    } finally {
      Arrays.fill(passphrase, '\0');
    }
  }

  public RSAPrivateKey privateKey() {
    return privateKey;
  }

  public RSAPublicKey publicKey() {
    return publicKey;
  }

  private static RSAPrivateKey readPrivateKey(Path path, char[] passphrase) {
    Object pem = readPem(path, "privée");
    PrivateKey key;
    try {
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
      if (pem instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
        InputDecryptorProvider decryptor =
            new JceOpenSSLPKCS8DecryptorProviderBuilder()
                .setProvider(new BouncyCastleProvider())
                .build(passphrase);
        key = converter.getPrivateKey(encrypted.decryptPrivateKeyInfo(decryptor));
      } else if (pem instanceof PrivateKeyInfo info) {
        key = converter.getPrivateKey(info);
      } else if (pem instanceof PEMKeyPair pair) {
        key = converter.getPrivateKey(pair.getPrivateKeyInfo());
      } else {
        throw new IllegalStateException("Clé privée JWT : format PEM non pris en charge");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Clé privée JWT illisible (passphrase incorrecte ou clé corrompue) : "
              + e.getClass().getSimpleName());
    }
    if (!(key instanceof RSAPrivateKey rsa)) {
      throw new IllegalStateException("Clé privée JWT : une clé RSA est attendue");
    }
    return rsa;
  }

  private static RSAPublicKey readPublicKey(Path path) {
    Object pem = readPem(path, "publique");
    PublicKey key;
    try {
      if (!(pem instanceof SubjectPublicKeyInfo info)) {
        throw new IllegalStateException("Clé publique JWT : format PEM non pris en charge");
      }
      key = new JcaPEMKeyConverter().getPublicKey(info);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Clé publique JWT illisible : " + e.getClass().getSimpleName());
    }
    if (!(key instanceof RSAPublicKey rsa)) {
      throw new IllegalStateException("Clé publique JWT : une clé RSA est attendue");
    }
    return rsa;
  }

  private static Object readPem(Path path, String kind) {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII);
        PEMParser parser = new PEMParser(reader)) {
      Object object = parser.readObject();
      if (object == null) {
        throw new IllegalStateException("Clé " + kind + " JWT : aucun objet PEM");
      }
      return object;
    } catch (IOException | RuntimeException e) {
      if (e instanceof IllegalStateException ise && ise.getCause() == null) {
        throw ise;
      }
      throw new IllegalStateException(
          "Clé " + kind + " JWT illisible (" + e.getClass().getSimpleName() + ")");
    }
  }
}
