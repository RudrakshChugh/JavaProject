package skillexchange.persistence;

import skillexchange.exception.SkillExchangeException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A piece of work that runs inside one database transaction and returns a value.
 * A generic functional interface: services pass one as a lambda to {@link Database#inTransaction}.
 */
@FunctionalInterface
public interface SqlWork<T> {
    T run(Connection connection) throws SQLException, SkillExchangeException;
}
