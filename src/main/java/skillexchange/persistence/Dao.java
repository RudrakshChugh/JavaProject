package skillexchange.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC plumbing for the Data Access Objects. An abstract class: it cannot be instantiated,
 * it only exists to be extended.
 *
 * Every query goes through a PreparedStatement with "?" placeholders. Values are bound, never
 * joined into the SQL text, so whatever a user types cannot change the meaning of the query.
 */
abstract class Dao {

    /** Dates are stored as text in one fixed layout, so comparing the text compares the times. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Runs a SELECT and maps every row. {@code Object... params} is a variable-length argument list. */
    protected static <T> List<T> list(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
                return result;
            }
        }
    }

    /** Runs a SELECT that returns at most one row. */
    protected static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = list(c, sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** Runs a SELECT whose single value is a whole number, such as COUNT(*) or SUM(...). */
    protected static int number(Connection c, String sql, Object... params) throws SQLException {
        return one(c, sql, rs -> rs.getInt(1), params).orElse(0);
    }

    /** Runs an UPDATE or DELETE and returns how many rows changed. */
    protected static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Runs an INSERT and returns the id the database generated for the new row. */
    protected static long insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("The database did not return an id for the new row");
                return keys.getLong(1);
            }
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int index = i + 1;                    // JDBC parameters are numbered from 1
            if (p instanceof LocalDateTime time) ps.setString(index, stamp(time));
            else if (p instanceof Enum<?> e) ps.setString(index, e.name());
            else if (p instanceof Boolean b) ps.setInt(index, b ? 1 : 0);
            else if (p instanceof byte[] bytes) ps.setBytes(index, bytes);
            else ps.setObject(index, p);          // String, Integer, Long, Double or null
        }
    }

    static String stamp(LocalDateTime time) {
        return time.format(STAMP);
    }

    static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : LocalDateTime.parse(text, STAMP);
    }

    /** Escapes the wildcards of LIKE, so a search for "100%" looks for those four characters. */
    static String like(String text) {
        return "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
