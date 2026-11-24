package skillexchange.web;

import skillexchange.exception.BookingException;
import skillexchange.model.*;
import skillexchange.service.BookingService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static skillexchange.web.Json.obj;

/**
 * The double-booking race from ConcurrencyDemo, returning results as data for the web page.
 * Every run uses fresh in-memory services, so the real saved data is never touched.
 */
final class RaceDemo {
    private static final int THREADS = 8;
    private static final int ROUNDS = 200;

    private RaceDemo() { }

    static Map<String, Object> run() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            return obj("threads", THREADS,
                    "unsafe", unsafe(pool),
                    "safe", sameSlot(pool),
                    "credits", creditRace(pool),
                    "stress", stress(pool));
        } finally {
            pool.shutdown();
        }
    }

    /** Check-then-act with no lock: every thread sees the slot as free, so every thread books it. */
    private static Map<String, Object> unsafe(ExecutorService pool) throws Exception {
        Student tutor = new Student("S0", "Ravi", null);
        TimeSlot slot = new TimeSlot("T1", new CreditSwapListing("L1", tutor, "Guitar", "", 45, 2), tomorrow());
        AtomicInteger sold = new AtomicInteger();
        runTogether(pool, i -> () -> {
            if (!slot.isBooked()) {
                Thread.sleep(20);          // the gap between "check" and "act"
                slot.markBooked();
                sold.incrementAndGet();
            }
            return null;
        });
        return obj("sold", sold.get());
    }

    /** Eight students book the same slot through the synchronized service. */
    private static List<Object> sameSlot(ExecutorService pool) throws Exception {
        BookingService service = new BookingService();
        Student tutor = new Student("S0", "Ravi", null);
        service.registerStudent(tutor, 0);
        service.addListing(new CreditSwapListing("L1", tutor, "Guitar", "", 45, 2));
        service.addSlot("L1", tomorrow());
        List<Student> learners = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            Student s = new Student("S" + i, "Student " + i, null);
            service.registerStudent(s, 5);
            learners.add(s);
        }
        return runTogether(pool, i -> () -> attempt(service, learners.get(i), "T1"));
    }

    /** One learner with 3 credits books eight different 2-credit slots at once. */
    private static Map<String, Object> creditRace(ExecutorService pool) throws Exception {
        BookingService service = new BookingService();
        Student tutor = new Student("S0", "Ravi", null);
        Student meera = new Student("S1", "Meera", null);
        service.registerStudent(tutor, 0);
        service.registerStudent(meera, 3);
        service.addListing(new CreditSwapListing("L1", tutor, "Guitar", "", 45, 2));
        for (int i = 0; i < THREADS; i++) service.addSlot("L1", tomorrow().plusHours(i));
        List<Object> lanes = runTogether(pool, i -> () -> attempt(service, meera, "T" + (i + 1)));
        return obj("start", 3, "cost", 2, "lanes", lanes, "balance", service.getLedger().getBalance(meera));
    }

    /** Repeats the same-slot race and counts rounds with exactly one winner. */
    private static Map<String, Object> stress(ExecutorService pool) throws Exception {
        int passed = 0;
        for (int r = 0; r < ROUNDS; r++) {
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
            if (wins.get() == 1 && service.getAllBookings().size() == 1) passed++;
        }
        return obj("rounds", ROUNDS, "passed", passed);
    }

    private static Object attempt(BookingService service, Student learner, String slotId) {
        String name = Thread.currentThread().getName();
        String thread = "Thread " + name.substring(name.lastIndexOf('-') + 1);
        try {
            Booking b = service.book(learner, slotId);
            return obj("thread", thread, "student", learner.getName(), "slot", slotId, "booked", true, "detail", b.getId());
        } catch (BookingException e) {
            return obj("thread", thread, "student", learner.getName(), "slot", slotId, "booked", false,
                    "detail", e.getClass().getSimpleName());
        }
    }

    /** Starts all tasks, then releases them at the same instant with a CountDownLatch. */
    private static <T> List<T> runTogether(ExecutorService pool, IntFunction<Callable<T>> taskFor) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<T> task = taskFor.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        return results;
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }
}
