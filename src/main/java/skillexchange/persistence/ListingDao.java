package skillexchange.persistence;

import skillexchange.model.Listing;
import skillexchange.model.ListingType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Data Access Object for the listings table. Each listing is read together with its tutor (a JOIN). */
public final class ListingDao extends Dao implements Repository<Listing> {

    static final String COLS = "l.id AS listing_id, l.type AS listing_type, l.skill, l.description, "
            + "l.duration_minutes, l.fee, l.credit_cost, " + StudentDao.cols("t", "tutor");
    static final String FROM = " FROM listings l JOIN students t ON t.id = l.tutor_id";

    /** The type column decides which subclass of Listing is created. */
    static Listing map(ResultSet rs) throws SQLException {
        return Listing.of(ListingType.valueOf(rs.getString("listing_type")), rs.getLong("listing_id"),
                StudentDao.map(rs, "tutor"), rs.getString("skill"), rs.getString("description"),
                rs.getInt("duration_minutes"), rs.getDouble("fee"), rs.getInt("credit_cost"));
    }

    @Override
    public String entityName() { return "listing"; }

    @Override
    public Optional<Listing> findById(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE l.id = ? AND l.active = 1", ListingDao::map, id);
    }

    @Override
    public List<Listing> findAll(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE l.active = 1 ORDER BY l.id", ListingDao::map);
    }

    public List<Listing> findByTutor(Connection c, long tutorId) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE l.tutor_id = ? AND l.active = 1 ORDER BY l.id",
                ListingDao::map, tutorId);
    }

    public long insert(Connection c, Listing listing, LocalDateTime at) throws SQLException {
        return insert(c, "INSERT INTO listings (tutor_id, type, skill, description, duration_minutes, fee, "
                        + "credit_cost, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listing.getTutor().getId(), listing.getType(), listing.getSkill(), listing.getDescription(),
                listing.getDurationMinutes(), listing.getFee(), listing.getCreditCost(), at);
    }

    /**
     * A listing is never deleted from the table: old bookings and chats still point at it.
     * It is marked inactive, which hides it everywhere.
     */
    public void deactivate(Connection c, long listingId) throws SQLException {
        update(c, "UPDATE listings SET active = 0 WHERE id = ?", listingId);
    }

    public int countActive(Connection c) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM listings WHERE active = 1");
    }
}
