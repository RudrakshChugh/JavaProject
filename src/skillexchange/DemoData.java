package skillexchange;

import skillexchange.model.*;
import skillexchange.service.BookingService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Sample data loaded on the very first run (or after --reset), so the app isn't empty. */
public final class DemoData {
    /** Every demo student signs in with this password. It is shown on the sign-in screen and in the README. */
    public static final String DEMO_PASSWORD = "demo123";

    private DemoData() { }

    public static void seed(BookingService service) throws Exception {
        LocalDateTime today = LocalDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0);

        // The demo's history really is in the past: the service's clock is wound back three days,
        // so the accounts, the first booking and its completion all carry honest dates, and the
        // "a session can only be confirmed once it has started" rule is followed rather than bypassed.
        service.setClock(fixedAt(today.minusDays(3)));

        Student asha = demoStudent("S1", "Asha", "asha@uni.edu");
        Student ravi = demoStudent("S2", "Ravi", "ravi@uni.edu");
        Student meera = demoStudent("S3", "Meera", "meera@uni.edu");
        Student kabir = demoStudent("S4", "Kabir", "kabir@uni.edu");
        service.registerStudent(asha, 5);
        service.registerStudent(ravi, 5);
        service.registerStudent(meera, 5);
        service.registerStudent(kabir, 5);

        service.addListing(new PaidListing("L1", asha, "Java Basics", "OOP fundamentals for beginners", 60, 200));
        service.addListing(new CreditSwapListing("L2", ravi, "Guitar", "Beginner chords and strumming", 45, 2));
        service.addListing(new FreeListing("L3", meera, "Calculus", "Limits and derivatives", 30));
        service.addListing(new CreditSwapListing("L4", kabir, "Spoken French", "Conversation practice", 30, 1));
        service.addListing(new PaidListing("L5", asha, "Data Structures", "Lists, trees and maps in Java", 90, 300));

        LocalDateTime d1 = today.plusDays(1);
        LocalDateTime d2 = d1.plusDays(1);
        service.addSlot("L1", d1);                    // T1
        service.addSlot("L1", d2);                    // T2
        service.addSlot("L2", d1.plusHours(1));       // T3
        service.addSlot("L2", d1.plusHours(3));       // T4
        service.addSlot("L3", d1.plusMinutes(15));    // T5
        service.addSlot("L4", d1.plusHours(6));       // T6
        service.addSlot("L4", d2.plusHours(6));       // T7
        service.addSlot("L5", d2.plusHours(3));       // T8

        // A little history, so balances and the session log aren't empty.
        // Three days ago Asha booked a guitar session that took place two days ago...
        TimeSlot past = service.addSlot("L2", today.minusDays(2).plusHours(1));   // T9
        Booking done = service.book(asha, past.getId());       // 2 of Asha's credits are held
        service.setClock(fixedAt(today.minusDays(2).plusHours(2)));
        service.completeSession(asha, done.getId());           // ...and confirmed it afterwards, so Ravi got 2

        service.setClock(Clock.systemDefaultZone());           // back to the present
        service.book(meera, "T6");                             // Meera has 1 credit held for French
    }

    private static Student demoStudent(String id, String name, String email) {
        return new Student(id, name, email, DEMO_PASSWORD.toCharArray());
    }

    private static Clock fixedAt(LocalDateTime time) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(time.atZone(zone).toInstant(), zone);
    }
}
