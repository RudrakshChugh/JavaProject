package skillexchange;

import skillexchange.model.*;
import skillexchange.service.BookingService;

import java.time.LocalDateTime;

/** Sample data loaded on the very first run (or after --reset), so the app isn't empty. */
public final class DemoData {
    private DemoData() { }

    public static void seed(BookingService service) throws Exception {
        Student asha = new Student("S1", "Asha", "asha@uni.edu");
        Student ravi = new Student("S2", "Ravi", "ravi@uni.edu");
        Student meera = new Student("S3", "Meera", "meera@uni.edu");
        Student kabir = new Student("S4", "Kabir", "kabir@uni.edu");
        service.registerStudent(asha, 5);
        service.registerStudent(ravi, 5);
        service.registerStudent(meera, 5);
        service.registerStudent(kabir, 5);

        service.addListing(new PaidListing("L1", asha, "Java Basics", "OOP fundamentals for beginners", 60, 200));
        service.addListing(new CreditSwapListing("L2", ravi, "Guitar", "Beginner chords and strumming", 45, 2));
        service.addListing(new FreeListing("L3", meera, "Calculus", "Limits and derivatives", 30));
        service.addListing(new CreditSwapListing("L4", kabir, "Spoken French", "Conversation practice", 30, 1));
        service.addListing(new PaidListing("L5", asha, "Data Structures", "Lists, trees and maps in Java", 90, 300));

        LocalDateTime d1 = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
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
        Booking done = service.book(asha, "T3");      // Asha swaps 2 credits for guitar
        service.completeSession(asha, done.getId());  // ...and confirms it, so Ravi gets 2
        service.book(meera, "T6");                    // Meera has 1 credit held for French
    }
}
