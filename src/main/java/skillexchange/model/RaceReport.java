package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/**
 * The outcome of the concurrency demonstration.
 *
 * @param unsafeBookings how often one slot was booked by {@code threads} threads with no lock
 * @param sameSlot       one lane per thread: everyone books the same slot through the real service
 * @param stressPassed   how many of {@code stressRounds} repeats of that race had exactly one winner
 * @param credits        one lane per thread: one learner with {@code creditsStart} credits books
 *                       {@code threads} different slots costing {@code creditCost} each
 */
public record RaceReport(int threads, int unsafeBookings, List<Lane> sameSlot, int stressRounds, int stressPassed,
                         int creditsStart, int creditCost, List<Lane> credits, int balanceAfter)
        implements Serializable {

    public RaceReport {
        sameSlot = List.copyOf(sameSlot);
        credits = List.copyOf(credits);
    }

    /** What one thread tried and how it ended. */
    public record Lane(String student, String thread, String slot, boolean booked, String detail)
            implements Serializable { }

    public int safeBookings() {
        return (int) sameSlot.stream().filter(Lane::booked).count();
    }

    public int safeRejections() {
        return sameSlot.size() - safeBookings();
    }

    /** The report as plain text, for the console demo. */
    public List<String> lines() {
        long paid = credits.stream().filter(Lane::booked).count();
        return List.of(
                "Without a lock: " + threads + " threads checked \"is it free?\" before any of them marked it booked.",
                "  The same slot was booked " + unsafeBookings + " time" + (unsafeBookings == 1 ? "" : "s") + ".",
                "Through BookingService (lock + transaction): " + safeBookings() + " booking succeeded, "
                        + safeRejections() + " were refused with SlotAlreadyBookedException.",
                "Repeated " + stressRounds + " times: " + stressPassed + " rounds had exactly one winner.",
                "One learner with " + creditsStart + " credits booked " + threads + " slots costing " + creditCost
                        + " each at once: " + paid + " booked, balance " + balanceAfter + ", never below zero.");
    }
}
