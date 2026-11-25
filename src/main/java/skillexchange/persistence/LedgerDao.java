package skillexchange.persistence;

import skillexchange.model.CreditTransaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** Data Access Object for the credit_transactions table: the history behind every balance. */
public final class LedgerDao extends Dao {

    public void insert(Connection c, long studentId, CreditTransaction.Type type, int amount, int balanceAfter,
                       Long bookingId, LocalDateTime at) throws SQLException {
        insert(c, "INSERT INTO credit_transactions (student_id, type, amount, balance_after, booking_id, at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", studentId, type, amount, balanceAfter, bookingId, at);
    }

    /**
     * Newest first. The LEFT JOINs add the skill and the other person of the booking an entry belongs
     * to; for the starting balance, which has no booking, those columns are NULL.
     */
    public List<CreditTransaction> history(Connection c, long studentId) throws SQLException {
        return list(c, "SELECT ct.id, ct.student_id, ct.type, ct.amount, ct.balance_after, ct.booking_id, ct.at,"
                        + " l.skill AS skill,"
                        + " CASE WHEN b.learner_id = ct.student_id THEN t.name ELSE le.name END AS other"
                        + " FROM credit_transactions ct"
                        + " LEFT JOIN bookings b ON b.id = ct.booking_id"
                        + " LEFT JOIN slots s ON s.id = b.slot_id"
                        + " LEFT JOIN listings l ON l.id = s.listing_id"
                        + " LEFT JOIN students t ON t.id = l.tutor_id"
                        + " LEFT JOIN students le ON le.id = b.learner_id"
                        + " WHERE ct.student_id = ? ORDER BY ct.id DESC",
                rs -> {
                    long booking = rs.getLong("booking_id");
                    Long bookingId = rs.wasNull() ? null : booking;
                    return new CreditTransaction(rs.getLong("id"), rs.getLong("student_id"),
                            CreditTransaction.Type.valueOf(rs.getString("type")), rs.getInt("amount"),
                            rs.getInt("balance_after"), bookingId, rs.getString("skill"), rs.getString("other"),
                            time(rs, "at"));
                }, studentId);
    }
}
