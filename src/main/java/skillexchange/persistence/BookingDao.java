package skillexchange.persistence;

import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Data Access Object for the bookings table. A booking is read with its learner, slot, listing and tutor. */
public final class BookingDao extends Dao implements Repository<Booking> {

    private static final String COLS = "b.id AS booking_id, b.status, b.held_credits, b.booked_at, "
            + StudentDao.cols("le", "learner") + ", " + SlotDao.COLS;
    private static final String FROM = " FROM bookings b JOIN students le ON le.id = b.learner_id"
            + " JOIN slots s ON s.id = b.slot_id JOIN listings l ON l.id = s.listing_id"
            + " JOIN students t ON t.id = l.tutor_id";

    private static Booking map(ResultSet rs) throws SQLException {
        return new Booking(rs.getLong("booking_id"), StudentDao.map(rs, "learner"), SlotDao.map(rs),
                BookingStatus.valueOf(rs.getString("status")), rs.getInt("held_credits"), time(rs, "booked_at"));
    }

    @Override
    public String entityName() { return "booking"; }

    @Override
    public Optional<Booking> findById(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE b.id = ?", BookingDao::map, id);
    }

    @Override
    public List<Booking> findAll(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " ORDER BY s.start_at DESC", BookingDao::map);
    }

    /** Bookings where the student is the learner or the tutor, newest session first. */
    public List<Booking> findInvolving(Connection c, long studentId) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE b.learner_id = ? OR l.tutor_id = ? ORDER BY s.start_at DESC, b.id DESC",
                BookingDao::map, studentId, studentId);
    }

    /** A confirmed booking this learner already has that overlaps the time range. */
    public Optional<Booking> findLearnerOverlap(Connection c, long learnerId, LocalDateTime start, LocalDateTime end) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE b.status = 'CONFIRMED' AND b.learner_id = ?"
                + " AND s.start_at < ? AND ? < s.end_at LIMIT 1", BookingDao::map, learnerId, end, start);
    }

    public Optional<Booking> findConfirmedOfListing(Connection c, long listingId) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE b.status = 'CONFIRMED' AND s.listing_id = ? LIMIT 1",
                BookingDao::map, listingId);
    }

    /** The newest bookings that were not cancelled, for the activity ticker. */
    public List<Booking> findRecent(Connection c, int limit) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE b.status <> 'CANCELLED' ORDER BY b.id DESC LIMIT ?",
                BookingDao::map, limit);
    }

    public int countTaughtBy(Connection c, long tutorId) throws SQLException {
        return number(c, "SELECT COUNT(*)" + FROM + " WHERE b.status = 'COMPLETED' AND l.tutor_id = ?", tutorId);
    }

    public long insert(Connection c, long slotId, long learnerId, int heldCredits, LocalDateTime at) throws SQLException {
        return insert(c, "INSERT INTO bookings (slot_id, learner_id, status, held_credits, booked_at) VALUES (?, ?, ?, ?, ?)",
                slotId, learnerId, BookingStatus.CONFIRMED, heldCredits, at);
    }

    public void setStatus(Connection c, long bookingId, BookingStatus status) throws SQLException {
        update(c, "UPDATE bookings SET status = ? WHERE id = ?", status, bookingId);
    }

    /** Credits a learner has in escrow right now: the holds of their confirmed bookings. */
    public int heldCredits(Connection c, long learnerId) throws SQLException {
        return number(c, "SELECT COALESCE(SUM(held_credits), 0) FROM bookings WHERE status = 'CONFIRMED' AND learner_id = ?",
                learnerId);
    }

    public int countWithStatus(Connection c, BookingStatus status) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM bookings WHERE status = ?", status);
    }
}
