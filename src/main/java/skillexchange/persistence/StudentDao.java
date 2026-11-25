package skillexchange.persistence;

import skillexchange.model.Student;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Data Access Object for the students table. */
public final class StudentDao extends Dao implements Repository<Student> {

    /** "t.id AS tutor_id, t.name AS tutor_name, t.email AS tutor_email" for alias t and prefix tutor. */
    static String cols(String alias, String prefix) {
        return alias + ".id AS " + prefix + "_id, " + alias + ".name AS " + prefix + "_name, "
                + alias + ".email AS " + prefix + "_email";
    }

    /** Reads a student written with {@link #cols}. No password data: this copy may leave the server. */
    static Student map(ResultSet rs, String prefix) throws SQLException {
        return new Student(rs.getLong(prefix + "_id"), rs.getString(prefix + "_name"), rs.getString(prefix + "_email"));
    }

    @Override
    public String entityName() { return "student"; }

    @Override
    public Optional<Student> findById(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + cols("st", "st") + " FROM students st WHERE st.id = ?", rs -> map(rs, "st"), id);
    }

    @Override
    public List<Student> findAll(Connection c) throws SQLException {
        return list(c, "SELECT " + cols("st", "st") + " FROM students st ORDER BY st.id", rs -> map(rs, "st"));
    }

    /** For signing in: the only query that loads the salt and the hash. */
    public Optional<Student> findByEmailWithPassword(Connection c, String email) throws SQLException {
        return one(c, "SELECT id, name, email, password_salt, password_hash FROM students WHERE email = ?",
                rs -> new Student(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                        rs.getBytes("password_salt"), rs.getBytes("password_hash")),
                email);
    }

    public boolean emailExists(Connection c, String email) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM students WHERE email = ?", email) > 0;
    }

    public long insert(Connection c, String name, String email, byte[] salt, byte[] hash, int credits,
                       LocalDateTime at) throws SQLException {
        return insert(c, "INSERT INTO students (name, email, password_salt, password_hash, credits, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", name, email, salt, hash, credits, at);
    }

    public int credits(Connection c, long studentId) throws SQLException {
        return number(c, "SELECT credits FROM students WHERE id = ?", studentId);
    }

    /** Adds (or, with a negative number, takes away) credits and returns the new balance. */
    public int addCredits(Connection c, long studentId, int delta) throws SQLException {
        update(c, "UPDATE students SET credits = credits + ? WHERE id = ?", delta, studentId);
        return credits(c, studentId);
    }

    public int count(Connection c) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM students");
    }
}
