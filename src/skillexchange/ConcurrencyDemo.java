package skillexchange;

import skillexchange.exception.BookingException;
import skillexchange.model.*;
import skillexchange.service.BookingService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 5 demo: many students try to book at the same moment.
 *   Part 1 shows the bug: an unsynchronised "check then book" lets several threads win.
 *   Part 2 shows the fix: the synchronized BookingService lets exactly one booking win.
 * Run: java -cp out skillexchange.ConcurrencyDemo
 */
public class ConcurrencyDemo {
    private static final int THREADS = 8;

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            unsafeBooking(pool);
            safeBookingSameSlot(pool);
            safeBookingCredits(pool);
            stressTest(pool, 200);
        } finally {
            pool.shutdown();
        }
    }

    // ---------- Part 1: the bug ----------

    /** Each thread checks whether the slot is free, then books it, with no lock in between. */
    private static void unsafeBooking(ExecutorService pool) throws Exception {
        System.out.println("=== Part 1: WITHOUT synchronisation ===");
        Student tutor = new Student("S0", "Ravi", "ravi@uni.edu");
        TimeSlot slot = new TimeSlot("T1", new CreditSwapListing("L1", tutor, "Guitar", "", 45, 2), tomorrow());
        List<String> winners = Collections.synchronizedList(new ArrayList<>());

        runTogether(pool, i -> () -> {
            if (!slot.isBooked()) {             // 1. check: every thread sees "free"...
                Thread.sleep(20);               //    (work such as charging credits happens here)
                slot.markBooked();              // 2. act: ...so every thread books it
                winners.add("Student" + (i + 1));
            }
            return null;
        });

        System.out.println("Threads that booked the same slot: " + winners.size() + " " + winners);
        System.out.println("BUG: one slot was sold " + winners.size() + " times.\n");
    }

    // ---------- Part 2: the fix ----------

    private static void safeBookingSameSlot(ExecutorService pool) throws Exception {
        System.out.println("=== Part 2a: WITH synchronisation, " + THREADS + " students race for one slot ===");
        BookingService service = new BookingService();
        Student tutor = new Student("S0", "Ravi", "ravi@uni.edu");
        service.registerStudent(tutor, 0);
        service.addListing(new CreditSwapListing("L1", tutor, "Guitar", "Beginner chords", 45, 2));
        service.addSlot("L1", tomorrow());

        List<Student> learners = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            Student s = new Student("S" + i, "Student" + i, null);
            service.registerStudent(s, 5);
            learners.add(s);
        }

        List<String> results = runTogether(pool, i -> () -> attemptBooking(service, learners.get(i), "T1"));
        results.forEach(r -> System.out.println("  " + r));
        System.out.println("Confirmed bookings: " + service.getAllBookings().size()
                + ", tutor's open slots: " + service.getAvailableSlots().size() + "\n");
    }

    /** One learner with 3 credits tries to book several 2-credit slots at once from many threads. */
    private static void safeBookingCredits(ExecutorService pool) throws Exception {
        System.out.println("=== Part 2b: one learner (3 credits) books " + THREADS + " different 2-credit slots at once ===");
        BookingService service = new BookingService();
        Student tutor = new Student("S0", "Ravi", "ravi@uni.edu");
        Student meera = new Student("S1", "Meera", "meera@uni.edu");
        service.registerStudent(tutor, 0);
        service.registerStudent(meera, 3);
        service.addListing(new CreditSwapListing("L1", tutor, "Guitar", "Beginner chords", 45, 2));
        for (int i = 0; i < THREADS; i++) service.addSlot("L1", tomorrow().plusHours(i));   // T1..T8, no overlap

        List<String> results = runTogether(pool, i -> () -> attemptBooking(service, meera, "T" + (i + 1)));
        results.forEach(r -> System.out.println("  " + r));
        System.out.println("Meera's balance: " + service.getLedger().getBalance(meera) + " (never negative)\n");
    }

    /** Repeats the one-slot race many times and confirms that each round has exactly one winner. */
    private static void stressTest(ExecutorService pool, int rounds) throws Exception {
        System.out.println("=== Stress test: " + rounds + " rounds x " + THREADS + " threads ===");
        int roundsWithOneWinner = 0;
        for (int r = 0; r < rounds; r++) {
            BookingService service = new BookingService();
            Student tutor = new Student("S0", "Tutor", null);
            service.registerStudent(tutor, 0);
            service.addListing(new FreeListing("L1", tutor, "Maths", "", 30));
            service.addSlot("L1", tomorrow());
            List<Student> learners = new ArrayList<>();
            for (int i = 1; i <= THREADS; i++) {
                Student s = new Student("S" + i, "S" + i, null);
                service.registerStudent(s, 0);
                learners.add(s);
            }
            AtomicInteger wins = new AtomicInteger();
            runTogether(pool, i -> () -> {
                try { service.book(learners.get(i), "T1"); wins.incrementAndGet(); }
                catch (BookingException ignored) { }
                return null;
            });
            if (wins.get() == 1 && service.getAllBookings().size() == 1) roundsWithOneWinner++;
        }
        System.out.println("Rounds with exactly one winner: " + roundsWithOneWinner + "/" + rounds
                + (roundsWithOneWinner == rounds ? "  PASS" : "  FAIL"));
    }

    // ---------- Helpers ----------

    private static String attemptBooking(BookingService service, Student learner, String slotId) {
        String who = Thread.currentThread().getName() + " (" + learner.getName() + " -> " + slotId + ")";
        try {
            Booking b = service.book(learner, slotId);
            return "BOOKED   " + who + ": " + b.getId();
        } catch (BookingException e) {
            return "rejected " + who + ": " + e.getClass().getSimpleName();
        }
    }

    /**
     * Starts THREADS tasks and releases them at the same instant with a CountDownLatch,
     * so they really do run at the same time. Returns their results in task order.
     */
    private static <T> List<T> runTogether(ExecutorService pool,
                                           java.util.function.IntFunction<Callable<T>> taskFor) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<T> task = taskFor.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();          // wait at the starting line
                return task.call();
            }));
        }
        ready.await();               // every thread is waiting...
        go.countDown();              // ...start them all at once
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        return results;
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }
}
