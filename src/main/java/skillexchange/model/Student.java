package skillexchange.model;

import java.io.Serializable;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A student, who can both offer skills (as a tutor) and book sessions (as a learner).
 *
 * Student objects travel from the server to the JavaFX client by serialization. The two password
 * fields are {@code transient}, so they are left out of every serialized copy: a salt or a hash
 * never leaves the server.
 */
public final class Student implements Serializable {
    private static final long serialVersionUID = 2L;

    private final long id;
    private final String name;
    private final String email;
    private final transient byte[] passwordSalt;
    private final transient byte[] passwordHash;

    /** A student as other people see them: no password data. */
    public Student(long id, String name, String email) {
        this(id, name, email, null, null);
    }

    /** A student loaded for signing in, with the stored salt and hash. */
    public Student(long id, String name, String email, byte[] passwordSalt, byte[] passwordHash) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Student name is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Student email is required");
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordSalt = passwordSalt;
        this.passwordHash = passwordHash;
    }

    /** True if the attempt is this student's password. Always false for a copy without password data. */
    public boolean checkPassword(char[] attempt) {
        if (passwordHash == null || attempt == null) return false;
        // isEqual takes the same time whether the first byte differs or the last, so timing gives nothing away.
        return MessageDigest.isEqual(passwordHash, Passwords.hash(attempt, passwordSalt));
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    /** The first letter of the name, for the round avatar in the user interface. */
    public String getInitial() { return name.substring(0, 1).toUpperCase(); }

    // Two Student objects are the same student if their ids match.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Student other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() { return name; }
}
