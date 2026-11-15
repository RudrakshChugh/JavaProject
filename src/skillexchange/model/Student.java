package skillexchange.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * A student who can both offer skills (as a tutor) and book sessions (as a learner).
 */
public class Student implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String email;

    public Student(String id, String name, String email) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Student id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Student name is required");
        this.id = id;
        this.name = name;
        this.email = email;
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
