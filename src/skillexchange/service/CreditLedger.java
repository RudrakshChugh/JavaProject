package skillexchange.service;

import skillexchange.exception.InsufficientCreditsException;
import skillexchange.model.CreditTransaction;
import skillexchange.model.CreditTransaction.Type;
import skillexchange.model.Student;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Credit balances for swap-based sessions, using escrow:
 *   book     -> learner's credits are HELD (taken from their balance)
 *   complete -> held credits are RELEASED to the tutor
 *   cancel   -> held credits are REFUNDED to the learner
 * The tutor only gets paid for sessions that actually happened, and a refund
 * can never fail because the tutor already spent the credits.
 *
 * Thread safety: every public method is synchronized, so balance checks and updates are atomic.
 * The ledger does not read the clock itself: BookingService passes in the time of each movement.
 */
public class CreditLedger implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Map<Student, Integer> balances = new HashMap<>();
    private final Map<String, Integer> heldByBooking = new HashMap<>();
    private final List<CreditTransaction> history = new ArrayList<>();

    public synchronized void openAccount(Student student, int initialCredits, LocalDateTime when) {
        if (balances.containsKey(student))
            throw new IllegalArgumentException("Account already exists for " + student);
        if (initialCredits < 0) throw new IllegalArgumentException("Initial credits cannot be negative");
        balances.put(student, initialCredits);
        history.add(new CreditTransaction(student, Type.INITIAL, initialCredits, null, when));
    }

    public synchronized boolean hasAccount(Student student) {
        return balances.containsKey(student);
    }

    public synchronized int getBalance(Student student) {
        Integer bal = balances.get(student);
        if (bal == null) throw new IllegalArgumentException("No credit account for " + student);
        return bal;
    }

    /** Takes credits from the learner and holds them against a booking. */
    public synchronized void hold(Student learner, int amount, String bookingId, LocalDateTime when)
            throws InsufficientCreditsException {
        int bal = getBalance(learner);
        if (bal < amount) throw new InsufficientCreditsException(learner, amount, bal);
        balances.put(learner, bal - amount);
        heldByBooking.put(bookingId, amount);
        history.add(new CreditTransaction(learner, Type.HOLD, -amount, bookingId, when));
    }

    /** Pays held credits to the tutor. Does nothing if nothing was held (paid or free sessions). */
    public synchronized void release(String bookingId, Student tutor, LocalDateTime when) {
        Integer amount = heldByBooking.remove(bookingId);
        if (amount == null) return;
        balances.merge(tutor, amount, Integer::sum);
        history.add(new CreditTransaction(tutor, Type.RELEASE, amount, bookingId, when));
    }

    /** Returns held credits to the learner. Does nothing if nothing was held. */
    public synchronized void refund(String bookingId, Student learner, LocalDateTime when) {
        Integer amount = heldByBooking.remove(bookingId);
        if (amount == null) return;
        balances.merge(learner, amount, Integer::sum);
        history.add(new CreditTransaction(learner, Type.REFUND, amount, bookingId, when));
    }

    public synchronized List<CreditTransaction> getHistory(Student student) {
        List<CreditTransaction> result = new ArrayList<>();
        for (CreditTransaction t : history) {
            if (t.getStudent().equals(student)) result.add(t);
        }
        return result;
    }
}
