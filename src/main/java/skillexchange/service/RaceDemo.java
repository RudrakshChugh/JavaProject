package skillexchange.service;

import skillexchange.exception.SkillExchangeException;
import skillexchange.model.Booking;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.RaceReport;
import skillexchange.model.RaceReport.Lane;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.persistence.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * The concurrency demonstration: eight threads try to book at the same instant.
 *
 * 1. The wrong way: a plain check-then-act with no lock. The slot is sold several times.
 * 2. The real BookingService on a throwaway database: exactly one booking succeeds.
 * 3. The same race repeated many times, to show the result is not luck.
 * 4. One learner books eight different slots at once with credits for only one of them:
 *    the balance never goes below zero.
 */
public final class RaceDemo {
    public static final int THREADS = 8;
    public static final int STRESS_ROUNDS = 200;
    private static final int CREDIT_COST = 3;       // a new student has 5 credits: enough for one, not for two

    private RaceDemo() { }

    /** A slot with no protection at all. volatile makes the field visible to all threads, but not the check-then-act atomic. */
    private static final class UnsafeSlot {
        volatile boolean booked;
    }

    public static RaceReport run() throws Exception {
        AtomicInteger threadNo = new AtomicInteger(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "Thread " + threadNo.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        Path dir = Files.createTempDirectory("skillexchange-race");
        try {
            int unsafe = unsafeRace(pool);

            Services s = Services.create(new Database(dir.resolve("race.db")));
            Student tutor = s.accounts().register("Race Tutor", "tutor@race.test", "race1234".toCharArray());
            List<Student> learners = new ArrayList<>();
            for (int i = 1; i <= THREADS; i++) {
                learners.add(s.accounts().register("Student " + (char) ('A' + i - 1), "student" + i + "@race.test",
                        "race1234".toCharArray()));
            }
            Listing free = s.bookings().createListing(tutor, ListingType.FREE, "Race condition", "", 30, 0, 0);
            LocalDateTime base = LocalDateTime.now().plusDays(1).withMinute(0).withSecond(0).withNano(0);

            // Everyone books the same slot.
            TimeSlot contested = slot(s, tutor, free, base);
            List<Lane> sameSlot = runTogether(pool, i -> () -> attempt(s, learners.get(i), contested));

            // The same race again and again, each time on a new slot an hour later.
            int passed = 0;
            for (int round = 1; round <= STRESS_ROUNDS; round++) {
                TimeSlot next = slot(s, tutor, free, base.plusHours(round));
                List<Lane> lanes = runTogether(pool, i -> () -> attempt(s, learners.get(i), next));
                if (lanes.stream().filter(Lane::booked).count() == 1) passed++;
            }

            // One learner, eight different slots at once, credits for one.
            Student solo = s.accounts().register("Solo Learner", "solo@race.test", "race1234".toCharArray());
            int startCredits = s.bookings().credits(solo).balance();
            Listing swap = s.bookings().createListing(tutor, ListingType.SWAP, "Credit race", "", 30, 0, CREDIT_COST);
            List<TimeSlot> costly = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                costly.add(slot(s, tutor, swap, base.plusDays(30).plusHours(i)));
            }
            List<Lane> credits = runTogether(pool, i -> () -> attempt(s, solo, costly.get(i)));

            return new RaceReport(THREADS, unsafe, sameSlot, STRESS_ROUNDS, passed, startCredits, CREDIT_COST,
                    credits, s.bookings().credits(solo).balance());
        } finally {
            pool.shutdown();            // always stop the threads and remove the files, even if the demo failed
            deleteRecursively(dir);
        }
    }

    private static TimeSlot slot(Services s, Student tutor, Listing listing, LocalDateTime start) throws SkillExchangeException {
        return s.bookings().addSlot(tutor, listing.getId(), start, SessionMode.IN_PERSON, "Lab 1");
    }

    /** One thread's try at booking, turned into a lane of the report whatever the outcome. */
    private static Lane attempt(Services s, Student learner, TimeSlot slot) {
        String thread = Thread.currentThread().getName();
        String slotName = "T" + slot.getId();
        try {
            Booking booking = s.bookings().book(learner, slot.getId());
            return new Lane(learner.getName(), thread, slotName, true, "Booked B" + booking.getId());
        } catch (SkillExchangeException refused) {
            // getSimpleName() of the actual object: SlotAlreadyBookedException, InsufficientCreditsException...
            return new Lane(learner.getName(), thread, slotName, false, refused.getClass().getSimpleName());
        }
    }

    /** Check, wait a moment, then act: the classic race condition. */
    private static int unsafeRace(ExecutorService pool) throws Exception {
        UnsafeSlot slot = new UnsafeSlot();
        AtomicInteger bookings = new AtomicInteger();      // a counter several threads can increment safely
        runTogether(pool, i -> () -> {
            if (!slot.booked) {                             // 1. check: every thread sees "free"...
                Thread.sleep(20);                           //    (work such as charging credits happens here)
                slot.booked = true;                         // 2. act: ...so every thread books it
                bookings.incrementAndGet();
            }
            return null;
        });
        return bookings.get();
    }

    /**
     * Starts THREADS tasks at exactly the same moment and collects their results.
     * A generic method: {@code <T>} is whatever the tasks return.
     *
     * Two latches do the timing. Each thread counts down "ready" and then waits on "go"; the
     * caller waits until all are ready and opens "go" once, releasing them together.
     */
    static <T> List<T> runTogether(ExecutorService pool, IntFunction<Callable<T>> taskFor) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<T> task = taskFor.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();             // wait at the starting line
                return task.call();
            }));
        }
        ready.await();                  // every thread is waiting...
        go.countDown();                 // ...start them all at once
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());       // get() waits for that task and returns its value
        }
        return results;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            // Deepest paths first, so a folder is empty by the time it is deleted.
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
