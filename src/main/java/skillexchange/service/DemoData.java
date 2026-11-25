package skillexchange.service;

import skillexchange.io.ModuleFileParser;
import skillexchange.model.Booking;
import skillexchange.model.Conversation;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.ModuleDraft;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Logger;

/** Sample data loaded on the very first run (or after --reset), so the app is not empty. */
public final class DemoData {
    private static final Logger LOG = Logger.getLogger(DemoData.class.getName());

    /** Every demo student signs in with this password. It is shown on the sign-in screen and in the README. */
    public static final String DEMO_PASSWORD = "demo1234";
    public static final String DEMO_EMAIL = "asha@uni.edu";

    private DemoData() { }

    public static void seed(Services s) throws Exception {
        LocalDateTime today = LocalDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0);

        // The demo's history really is in the past: the clock is wound back three days, so the
        // accounts, the first booking and its completion all carry honest dates, and the rule
        // "a session can only be confirmed once it has started" is followed rather than bypassed.
        s.clock().set(fixedAt(today.minusDays(3)));
        try {
            Student asha = student(s, "Asha", "asha@uni.edu");
            Student ravi = student(s, "Ravi", "ravi@uni.edu");
            Student meera = student(s, "Meera", "meera@uni.edu");
            Student kabir = student(s, "Kabir", "kabir@uni.edu");

            BookingService b = s.bookings();
            Listing java = b.createListing(asha, ListingType.PAID, "Java Basics", "OOP fundamentals for beginners", 60, 200, 0);
            Listing guitar = b.createListing(ravi, ListingType.SWAP, "Guitar", "Beginner chords and strumming", 45, 0, 2);
            Listing calculus = b.createListing(meera, ListingType.FREE, "Calculus", "Limits and derivatives", 30, 0, 0);
            Listing french = b.createListing(kabir, ListingType.SWAP, "Spoken French", "Conversation practice", 30, 0, 1);
            Listing dsa = b.createListing(asha, ListingType.PAID, "Data Structures", "Lists, trees and maps in Java", 90, 300, 0);
            Listing python = b.createListing(ravi, ListingType.SWAP, "Python for Beginners", "From print() to loops, with exercises", 60, 0, 1);

            LocalDateTime d1 = today.plusDays(1);
            LocalDateTime d2 = d1.plusDays(1);
            String online = "https://meet.example.com/skill-exchange";
            b.addSlot(asha, java.getId(), d1, SessionMode.ONLINE, online + "-java");
            b.addSlot(asha, java.getId(), d2, SessionMode.IN_PERSON, "Library, discussion room 2");
            b.addSlot(ravi, guitar.getId(), d1.plusHours(1), SessionMode.IN_PERSON, "Music room, Student Centre");
            b.addSlot(ravi, guitar.getId(), d1.plusHours(3), SessionMode.IN_PERSON, "Music room, Student Centre");
            b.addSlot(meera, calculus.getId(), d1.plusMinutes(15), SessionMode.ONLINE, online + "-calculus");
            TimeSlot frenchSlot = b.addSlot(kabir, french.getId(), d1.plusHours(6), SessionMode.ONLINE, online + "-french");
            b.addSlot(kabir, french.getId(), d2.plusHours(6), SessionMode.ONLINE, online + "-french");
            b.addSlot(asha, dsa.getId(), d2.plusHours(3), SessionMode.IN_PERSON, "CS block, lab 4");
            TimeSlot pythonSlot = b.addSlot(ravi, python.getId(), d2.plusHours(1), SessionMode.ONLINE, online + "-python");
            b.addSlot(ravi, python.getId(), d2.plusDays(1).plusHours(1), SessionMode.ONLINE, online + "-python");

            // Learning modules are read from text files, the same way a tutor imports their own.
            for (ModuleDraft draft : ModuleFileParser.parseResource("/modules/python-basics.txt")) {
                s.learning().createModule(ravi, python.getId(), draft);
            }
            for (ModuleDraft draft : ModuleFileParser.parseResource("/modules/java-basics.txt")) {
                s.learning().createModule(asha, java.getId(), draft);
            }

            // A little history, so balances and the session list are not empty.
            // Three days ago Asha booked a guitar session that took place two days ago...
            TimeSlot past = b.addSlot(ravi, guitar.getId(), today.minusDays(2).plusHours(1), SessionMode.IN_PERSON,
                    "Music room, Student Centre");
            Booking done = b.book(asha, past.getId());              // 2 of Asha's credits are held
            s.clock().set(fixedAt(today.minusDays(2).plusHours(2)));
            b.complete(asha, done.getId());                         // ...and confirmed it afterwards, so Ravi got 2

            // Yesterday Meera asked Kabir about the French session, then booked it.
            s.clock().set(fixedAt(today.minusDays(1).plusHours(4)));
            Conversation chat = s.chat().open(meera, french.getId());
            s.chat().send(meera, chat.id(), "Hi Kabir! I'm a complete beginner. Is that okay for your session?");
            s.clock().set(fixedAt(today.minusDays(1).plusHours(4).plusMinutes(6)));
            s.chat().send(kabir, chat.id(), "Of course. We'll start with greetings and ordering food. Bring a notebook.");
            s.clock().set(fixedAt(today.minusDays(1).plusHours(4).plusMinutes(9)));
            s.chat().send(meera, chat.id(), "Perfect, booking it now. See you online!");
            b.book(meera, frenchSlot.getId());                      // Meera has 1 credit held for French

            // Yesterday evening Asha asked Ravi about the Python session, then booked it too.
            s.clock().set(fixedAt(today.minusDays(1).plusHours(8)));
            Conversation pythonChat = s.chat().open(asha, python.getId());
            s.chat().send(asha, pythonChat.id(), "Hi Ravi, I have never written Python. Do I need to install anything first?");
            s.clock().set(fixedAt(today.minusDays(1).plusHours(8).plusMinutes(4)));
            s.chat().send(ravi, pythonChat.id(), "Nothing at all. We will use an online editor; the link is in the booking. "
                    + "Try the two exercise modules in Learn afterwards.");
            s.clock().set(fixedAt(today.minusDays(1).plusHours(8).plusMinutes(7)));
            s.chat().send(asha, pythonChat.id(), "Great, I have booked the first slot.");
            b.book(asha, pythonSlot.getId());                       // Asha has 1 credit held for Python
        } finally {
            s.clock().reset();                                      // back to the present, whatever happened
        }
        LOG.info("Demo data loaded: 4 students, 6 listings. Sign in as " + DEMO_EMAIL + " with password " + DEMO_PASSWORD);
    }

    private static Student student(Services s, String name, String email) throws Exception {
        return s.accounts().register(name, email, DEMO_PASSWORD.toCharArray());
    }

    private static Clock fixedAt(LocalDateTime time) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(time.atZone(zone).toInstant(), zone);
    }
}
