package skillexchange.persistence;

import skillexchange.model.ListingType;
import skillexchange.model.SessionMode;
import skillexchange.model.TimeSlot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Data Access Object for the slots table. */
public final class SlotDao extends Dao implements Repository<TimeSlot> {

    static final String COLS = "s.id AS slot_id, s.start_at, s.mode, s.place, s.booked, " + ListingDao.COLS;
    static final String FROM = " FROM slots s JOIN listings l ON l.id = s.listing_id JOIN students t ON t.id = l.tutor_id";

    // Two time ranges overlap when each one starts before the other ends.
    private static final String OVERLAPS = " AND s.start_at < ? AND ? < s.end_at";

    static TimeSlot map(ResultSet rs) throws SQLException {
        return new TimeSlot(rs.getLong("slot_id"), ListingDao.map(rs), time(rs, "start_at"),
                SessionMode.valueOf(rs.getString("mode")), rs.getString("place"), rs.getInt("booked") == 1);
    }

    @Override
    public String entityName() { return "session slot"; }

    @Override
    public Optional<TimeSlot> findById(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE s.id = ?", SlotDao::map, id);
    }

    @Override
    public List<TimeSlot> findAll(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " ORDER BY s.start_at", SlotDao::map);
    }

    /**
     * Open slots a learner can still book, soonest first: not booked, not started, the listing still
     * exists, and the tutor is not attending someone else's session at that time. The text filter
     * matches the skill, the tutor's name or the description; a null type means every type.
     */
    public List<TimeSlot> findOpenUpcoming(Connection c, LocalDateTime now, String text, ListingType type) throws SQLException {
        String pattern = like(text == null ? "" : text.strip());
        return list(c, "SELECT " + COLS + FROM
                        + " WHERE s.booked = 0 AND s.removed = 0 AND l.active = 1 AND s.start_at > ?"
                        + " AND (lower(l.skill) LIKE ? ESCAPE '\\' OR lower(t.name) LIKE ? ESCAPE '\\'"
                        + "      OR lower(l.description) LIKE ? ESCAPE '\\')"
                        + " AND (? IS NULL OR l.type = ?)"
                        + " AND NOT EXISTS (SELECT 1 FROM bookings b JOIN slots bs ON bs.id = b.slot_id"
                        + "                 WHERE b.status = 'CONFIRMED' AND b.learner_id = l.tutor_id"
                        + "                   AND bs.start_at < s.end_at AND s.start_at < bs.end_at)"
                        + " ORDER BY s.start_at, s.id",
                SlotDao::map, now, pattern, pattern, pattern,
                type == null ? null : type.name(), type == null ? null : type.name());
    }

    public List<TimeSlot> findByTutor(Connection c, long tutorId) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE l.tutor_id = ? AND s.removed = 0 AND l.active = 1 ORDER BY s.start_at",
                SlotDao::map, tutorId);
    }

    /** Any slot of this tutor, booked or open, that overlaps the time range. Removed slots do not count. */
    public Optional<TimeSlot> findTutorOverlap(Connection c, long tutorId, LocalDateTime start, LocalDateTime end) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE l.tutor_id = ? AND s.removed = 0" + OVERLAPS + " LIMIT 1",
                SlotDao::map, tutorId, end, start);
    }

    /** A booked slot this person teaches that overlaps the time range. */
    public Optional<TimeSlot> findTeachingOverlap(Connection c, long tutorId, LocalDateTime start, LocalDateTime end) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE l.tutor_id = ? AND s.booked = 1" + OVERLAPS + " LIMIT 1",
                SlotDao::map, tutorId, end, start);
    }

    public long insert(Connection c, long listingId, LocalDateTime start, LocalDateTime end, SessionMode mode,
                       String place) throws SQLException {
        return insert(c, "INSERT INTO slots (listing_id, start_at, end_at, mode, place) VALUES (?, ?, ?, ?, ?)",
                listingId, start, end, mode, place);
    }

    public void setBooked(Connection c, long slotId, boolean booked) throws SQLException {
        update(c, "UPDATE slots SET booked = ? WHERE id = ?", booked, slotId);
    }

    /**
     * A removed slot stays in the table, marked as removed, because an old cancelled booking may
     * still point at it. Every query for open slots leaves removed slots out.
     */
    public void remove(Connection c, long slotId) throws SQLException {
        update(c, "UPDATE slots SET removed = 1 WHERE id = ?", slotId);
    }

    /** Removes the open slots of a listing. Booked slots stay: they are part of the session history. */
    public int removeOpenOfListing(Connection c, long listingId) throws SQLException {
        return update(c, "UPDATE slots SET removed = 1 WHERE listing_id = ? AND booked = 0", listingId);
    }

    public int countOpenUpcoming(Connection c, LocalDateTime now) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM slots s JOIN listings l ON l.id = s.listing_id"
                + " WHERE s.booked = 0 AND s.removed = 0 AND l.active = 1 AND s.start_at > ?", now);
    }
}
