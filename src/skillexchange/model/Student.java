package skillexchange.model;

import java.io.Serializable;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A student who can both offer skills (as a tutor) and book sessions (as a learner).
 */
public class Student implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String email;
    // Only a salted hash of the password is stored (see Passwords). Both are null for a student
    // without a password: the concurrency demos, and data saved before passwords existed.
    private final byte[] passwordSalt;
    private final byte[] passwordHash;

    public static final int MIN_PASSWORD_LENGTH = Passwords.MIN_LENGTH;
    public static final int MAX_PASSWORD_LENGTH = Passwords.MAX_LENGTH;

    /** A student with no password. They exist in the system but cannot sign in. */
    public Student(String id, String name, String email) {
        this(id, name, email, null);
    }

    public Student(String id, String name, String email, char[] password) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Student id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Student name is required");
        if (password != null && (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH))
            throw new IllegalArgumentException("Password must be " + MIN_PASSWORD_LENGTH + " to "
                    + MAX_PASSWORD_LENGTH + " characters long");
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordSalt = password == null ? null : Passwords.newSalt();
        this.passwordHash = password == null ? null : Passwords.hash(password, passwordSalt);
    }

    public boolean hasPassword() { return passwordHash != null; }

    /** True if the attempt is this student's password. Always false for a student without one. */
    public boolean checkPassword(char[] attempt) {
        if (passwordHash == null || attempt == null) return false;
        // isEqual takes the same time whether the first byte differs or the last, so timing gives nothing away.
        return MessageDigest.isEqual(passwordHash, Passwords.hash(attempt, passwordSalt));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    // Two Student objects are the same student if their ids match.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Student other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() { return name + " (" + id + ")"; }
}
