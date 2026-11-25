package skillexchange.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** A read-only question to the database that cannot break a business rule. */
@FunctionalInterface
public interface SqlQuery<T> {
    T run(Connection connection) throws SQLException;
}
