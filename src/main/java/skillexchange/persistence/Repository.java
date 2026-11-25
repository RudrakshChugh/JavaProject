package skillexchange.persistence;

import skillexchange.exception.NotFoundException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * What every Data Access Object can do, whatever it stores. A generic interface: {@code T} is the
 * kind of object, so {@code Repository<Student>} finds students and {@code Repository<Booking>}
 * finds bookings with the same two methods.
 *
 * Every method takes the Connection to use, so several DAO calls can share one transaction.
 */
public interface Repository<T> {

    /** An Optional instead of null: the caller cannot forget the "not found" case. */
    Optional<T> findById(Connection c, long id) throws SQLException;

    List<T> findAll(Connection c) throws SQLException;

    /** The word used in "No ... with id 7". */
    String entityName();

    /** A default method: written once here, inherited by every DAO. */
    default T require(Connection c, long id) throws SQLException, NotFoundException {
        return findById(c, id).orElseThrow(() -> new NotFoundException(entityName(), id));
    }
}
