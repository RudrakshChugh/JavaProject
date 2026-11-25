package skillexchange.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skillexchange.api.Event;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.AuthException;
import skillexchange.exception.ConnectionLostException;
import skillexchange.exception.InsufficientCreditsException;
import skillexchange.exception.SlotAlreadyBookedException;
import skillexchange.exception.ValidationException;
import skillexchange.model.Booking;
import skillexchange.model.Conversation;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.RaceReport;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.persistence.Database;
import skillexchange.server.SkillExchangeServer;
import skillexchange.service.Services;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real server on a free port and real clients talking to it over sockets. */
class ClientServerTest {

    @TempDir
    Path dir;

    private SkillExchangeServer server;
    private RemoteApi tutorSide;
    private RemoteApi learnerSide;
    private SkillExchangeApi tutor;
    private SkillExchangeApi learner;

    @BeforeEach
    void setUp() throws Exception {
        server = new SkillExchangeServer(Services.create(new Database(dir.resolve("test.db"))), 0);
        server.start();
        tutorSide = RemoteApi.connect("localhost", server.getPort());
        learnerSide = RemoteApi.connect("localhost", server.getPort());
        tutor = tutorSide.api();
        learner = learnerSide.api();
    }

    @AfterEach
    void tearDown() {
        tutorSide.close();
        learnerSide.close();
        server.close();
    }

    @Test
    void methodsWithoutTheOpenAnnotationNeedSignIn() throws Exception {
        assertEquals(0, learner.stats().students());                 // @Open: allowed
        assertTrue(learner.openSlots("", null).isEmpty());           // browsing is open to everyone
        assertThrows(AuthException.class, () -> learner.myBookings());
        assertThrows(AuthException.class, () -> learner.book(1));
    }

    @Test
    void exceptionsCrossTheNetworkWithTheirTypeAndFields() throws Exception {
        ValidationException bad = assertThrows(ValidationException.class,
                () -> learner.register("Asha", "not-an-email", "pass1234".toCharArray()));
        assertEquals("email", bad.getField());

        tutor.register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        learner.register("Asha", "asha@test.edu", "pass1234".toCharArray());
        Listing costly = tutor.createListing(ListingType.SWAP, "Guitar", "", 60, 0, 9);
        TimeSlot slot = tutor.addSlot(costly.getId(), LocalDateTime.now().plusDays(1), SessionMode.IN_PERSON, "Room 1");
        InsufficientCreditsException poor = assertThrows(InsufficientCreditsException.class, () -> learner.book(slot.getId()));
        assertEquals(9, poor.getRequired());
    }

    @Test
    void twoClientsBookChatAndSeeEachOthersChanges() throws Exception {
        BlockingQueue<Event> tutorEvents = new LinkedBlockingQueue<>();
        tutorSide.onEvent(tutorEvents::add);

        Student ravi = tutor.register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        Student asha = learner.register("Asha", "asha@test.edu", "pass1234".toCharArray());
        Listing guitar = tutor.createListing(ListingType.SWAP, "Guitar", "Chords", 60, 0, 2);
        tutor.addSlot(guitar.getId(), LocalDateTime.now().plusDays(1), SessionMode.ONLINE, "https://meet.example.com/g");

        // Browsing does not reveal the meeting link; the booking does.
        List<TimeSlot> open = learner.openSlots("guitar", ListingType.SWAP);
        assertEquals(1, open.size());
        assertNull(open.get(0).getPlace());
        assertEquals(ravi, open.get(0).getTutor());

        Booking booking = learner.book(open.get(0).getId());
        assertEquals("https://meet.example.com/g", booking.getSlot().getPlace());
        assertEquals(asha, tutor.myBookings().get(0).getLearner());
        assertEquals(3, learner.credits().balance());
        // A third student, on a third connection, is too late for the same slot.
        try (RemoteApi third = RemoteApi.connect("localhost", server.getPort())) {
            third.api().register("Meera", "meera@test.edu", "pass1234".toCharArray());
            assertThrows(SlotAlreadyBookedException.class, () -> third.api().book(open.get(0).getId()));
        }

        // The learner writes; the tutor's client is told without asking.
        Conversation chat = learner.openConversation(guitar.getId());
        learner.sendMessage(chat.id(), "Should I bring my own guitar?");
        Event.NewMessage pushed = null;
        Event e;
        while ((e = tutorEvents.poll(5, TimeUnit.SECONDS)) != null) {
            if (e instanceof Event.NewMessage m) {
                pushed = m;
                break;
            }
        }
        assertNotNull(pushed, "the tutor should have been pushed the message");
        assertEquals("Should I bring my own guitar?", pushed.message().text());
        assertEquals(asha, pushed.message().sender());
        assertEquals(1, tutor.messages(chat.id()).size());

        // After signing out the same connection is a guest again.
        learner.logout();
        assertThrows(AuthException.class, () -> learner.myBookings());
        assertEquals(asha, learner.login("asha@test.edu", "pass1234".toCharArray()));
    }

    @Test
    void theRaceDemoHasExactlyOneWinner() throws Exception {
        RaceReport report = learner.raceDemo();
        assertEquals(1, report.safeBookings());
        assertEquals(report.threads() - 1, report.safeRejections());
        assertTrue(report.unsafeBookings() >= 1);
        assertEquals(report.stressRounds(), report.stressPassed(), "every repeat has exactly one winner");
        // Credits for one session only: one lane books, the others are refused, the balance stays positive.
        assertEquals(1, report.credits().stream().filter(l -> l.booked()).count());
        assertTrue(report.credits().stream().filter(l -> !l.booked())
                .allMatch(l -> l.detail().equals("InsufficientCreditsException")));
        assertEquals(report.creditsStart() - report.creditCost(), report.balanceAfter());
    }

    @Test
    void aStoppedServerIsReportedAsALostConnection() throws Exception {
        learner.stats();
        server.close();
        assertThrows(ConnectionLostException.class, () -> {
            for (int i = 0; i < 20; i++) {
                learner.stats();
                Thread.sleep(50);
            }
        });
    }
}
