package skillexchange.service;

import skillexchange.exception.InsufficientCreditsException;
import skillexchange.model.Booking;
import skillexchange.model.CreditTransaction.Type;
import skillexchange.model.Student;
import skillexchange.persistence.LedgerDao;
import skillexchange.persistence.StudentDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * Credit balances for swap-based sessions, using escrow:
 *   book     -> the learner's credits are HELD (taken from their balance)
 *   complete -> the held credits are RELEASED to the tutor
 *   cancel   -> the held credits are REFUNDED to the learner
 * The tutor only gets paid for sessions that actually happened, and a refund can never fail
 * because the tutor already spent the credits.
 *
 * Every method takes the Connection of the caller's transaction. So a balance change and the
 * booking it belongs to are committed together, or rolled back together.
 */
final class CreditLedger {
    private final StudentDao students;
    private final LedgerDao ledger;

    CreditLedger(StudentDao students, LedgerDao ledger) {
        this.students = students;
        this.ledger = ledger;
    }

    /** Gives a new student their starting balance. */
    void open(Connection c, long studentId, int credits, LocalDateTime at) throws SQLException {
        int balance = students.addCredits(c, studentId, credits);
        ledger.insert(c, studentId, Type.INITIAL, credits, balance, null, at);
    }

    /** Takes credits from the learner and holds them against a booking. */
    void hold(Connection c, Student learner, int amount, long bookingId, LocalDateTime at)
            throws SQLException, InsufficientCreditsException {
        int balance = students.credits(c, learner.getId());
        if (balance < amount) throw new InsufficientCreditsException(learner.getName(), amount, balance);
        int after = students.addCredits(c, learner.getId(), -amount);
        ledger.insert(c, learner.getId(), Type.HOLD, -amount, after, bookingId, at);
    }

    /** Pays the held credits to the tutor. Does nothing if nothing was held (paid or free sessions). */
    void release(Connection c, Booking booking, LocalDateTime at) throws SQLException {
        move(c, booking, booking.getTutor(), Type.RELEASE, at);
    }

    /** Returns the held credits to the learner. Does nothing if nothing was held. */
    void refund(Connection c, Booking booking, LocalDateTime at) throws SQLException {
        move(c, booking, booking.getLearner(), Type.REFUND, at);
    }

    private void move(Connection c, Booking booking, Student to, Type type, LocalDateTime at) throws SQLException {
        int amount = booking.getHeldCredits();
        if (amount == 0) return;
        int after = students.addCredits(c, to.getId(), amount);
        ledger.insert(c, to.getId(), type, amount, after, booking.getId(), at);
    }
}
