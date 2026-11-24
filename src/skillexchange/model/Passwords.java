package skillexchange.model;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Turns a password into a hash that is safe to store. The password itself is never kept.
 *
 * PBKDF2 is deliberately slow (it repeats the hash many thousands of times), so that someone who
 * steals the saved file cannot try millions of guesses per second. Each student gets their own
 * random salt, so two students with the same password still have different hashes.
 * Everything here ships with the JDK; no library is needed.
 */
final class Passwords {
    static final int MIN_LENGTH = 6;
    static final int MAX_LENGTH = 72;
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() { }

    static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime cannot hash passwords", e);
        } finally {
            spec.clearPassword();
        }
    }
}
