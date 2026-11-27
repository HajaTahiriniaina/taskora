package com.taskora.identity.adapter.out.security;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Moteur bcrypt natif (B1) : {@code crypt_rn} de libxcrypt ({@code libcrypt.so.1}) appelé par l'API
 * FFM, même algorithme Openwall en C que PHP.
 *
 * <p>Mémoire : la bibliothèque et le symbole sont résolus une fois ({@link Arena#global()}) ;
 * chaque appel alloue ses propres tampons dans une {@link Arena#ofConfined()} (aucun état natif
 * partagé : sûr en multi-thread), les efface avant de fermer l'arène, et ne lit le résultat que
 * dans le champ {@code output} de la {@code struct crypt_data} qu'il a lui-même allouée (lecture
 * bornée).
 *
 * <p>Exige {@code --enable-native-access=ALL-UNNAMED} (image Docker, Surefire/Failsafe).
 */
final class LibxcryptBcryptEngine implements BcryptEngine {
  static final String LIBRARY = "libcrypt.so.1";

  /**
   * {@code sizeof(struct crypt_data)} de libxcrypt 4.x : output[384] + setting[384] + input[512] +
   * reserved[767] + initialized[1] + internal[30720] = 32 768 (vérifié par {@code sizeof} contre
   * {@code crypt.h} de libxcrypt 4.5.1, paquet Ubuntu {@code libcrypt-dev 1:4.5.1-1}).
   */
  static final int CRYPT_DATA_SIZE = 32_768;

  /** {@code CRYPT_OUTPUT_SIZE} : taille du champ {@code output}, à l'offset 0 de la structure. */
  static final int CRYPT_OUTPUT_SIZE = 384;

  /** Entrée effective maximale de bcrypt (après pré-hachage Symfony). */
  static final int MAX_INPUT_BYTES = 72;

  /** Réglage maximal accepté (hash complet bcrypt : 60 caractères), bien sous CRYPT_OUTPUT_SIZE. */
  static final int MAX_SETTING_LENGTH = 60;

  private static final String PROPERTY = "identity.password.bcrypt-engine";

  private final MethodHandle cryptRn;

  /** Résout {@code crypt_rn} dans {@value #LIBRARY} ; échec explicite si introuvable. */
  LibxcryptBcryptEngine() {
    this(LIBRARY);
  }

  /**
   * Résout {@code crypt_rn} dans la bibliothèque nommée.
   *
   * @throws IllegalStateException si la bibliothèque ou le symbole est introuvable
   */
  LibxcryptBcryptEngine(String library) {
    SymbolLookup lookup;
    try {
      lookup = SymbolLookup.libraryLookup(library, Arena.global());
    } catch (IllegalArgumentException | IllegalCallerException e) {
      throw new IllegalStateException(unavailable(library), e);
    }
    MemorySegment symbol =
        lookup
            .find("crypt_rn")
            .orElseThrow(() -> new IllegalStateException(unavailable(library) + " (crypt_rn)"));
    // char *crypt_rn(const char *phrase, const char *setting, void *data, int size)
    this.cryptRn =
        Linker.nativeLinker()
            .downcallHandle(
                symbol,
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
  }

  private static String unavailable(String library) {
    return "Moteur bcrypt natif indisponible : bibliothèque "
        + library
        + " introuvable. Installer libxcrypt (libcrypt1) ou choisir "
        + PROPERTY
        + "=java (développement uniquement).";
  }

  @Override
  public @Nullable String crypt(byte[] input, String setting) {
    if (input.length > MAX_INPUT_BYTES || containsNul(input) || !isBcryptSetting(setting)) {
      return null;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment phrase = arena.allocate(input.length + 1L);
      MemorySegment salt = arena.allocate(setting.length() + 1L);
      MemorySegment data = arena.allocate(CRYPT_DATA_SIZE);
      try {
        // Segments alloués à zéro : terminaisons NUL et struct crypt_data initialisée.
        MemorySegment.copy(input, 0, phrase, ValueLayout.JAVA_BYTE, 0, input.length);
        byte[] settingBytes = setting.getBytes(StandardCharsets.US_ASCII);
        MemorySegment.copy(settingBytes, 0, salt, ValueLayout.JAVA_BYTE, 0, settingBytes.length);
        MemorySegment result =
            (MemorySegment) cryptRn.invokeExact(phrase, salt, data, CRYPT_DATA_SIZE);
        if (result.address() != data.address()) {
          // NULL (échec) ; tout autre pointeur serait inattendu : jamais lu.
          return null;
        }
        String hash = data.asSlice(0, CRYPT_OUTPUT_SIZE).getString(0, StandardCharsets.US_ASCII);
        return hash.isEmpty() || hash.charAt(0) == '*' ? null : hash;
      } finally {
        phrase.fill((byte) 0);
        salt.fill((byte) 0);
        data.fill((byte) 0);
      }
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      // invokeExact ne déclare rien d'autre ; garde pour le compilateur.
      throw new IllegalStateException("Appel crypt_rn impossible", e);
    }
  }

  private static boolean containsNul(byte[] input) {
    for (byte b : input) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Seuls les réglages bcrypt ($2a$, $2b$, $2y$), ASCII imprimables et au plus 60 caractères
   * atteignent crypt_rn : aucun autre algorithme de libxcrypt (DES, MD5, SHA-crypt) n'est
   * joignable.
   */
  private static boolean isBcryptSetting(String setting) {
    if (setting.length() < 7 || setting.length() > MAX_SETTING_LENGTH) {
      return false;
    }
    if (!(setting.startsWith("$2a$") || setting.startsWith("$2b$") || setting.startsWith("$2y$"))) {
      return false;
    }
    for (int i = 0; i < setting.length(); i++) {
      char c = setting.charAt(i);
      if (c < 0x21 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }
}
