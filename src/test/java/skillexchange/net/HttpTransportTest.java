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
import skillexchange.exception.ValidationException;
import skillexchange.model.Booking;
import skillexchange.model.Conversation;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.persistence.Database;
import skillexchange.server.SkillExchangeServer;
import skillexchange.service.Services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same server reached through its HTTP gateway instead of its socket: the way the desktop
 * application talks to a server on a web hosting service.
 */
class HttpTransportTest {

    @TempDir
    Path dir;

    private SkillExchangeServer server;
    private URI address;
    private RemoteApi tutorSide;
    private RemoteApi learnerSide;
    private SkillExchangeApi tutor;
    private SkillExchangeApi learner;

    @BeforeEach
    void setUp() throws Exception {
        // A socket port and an HTTP port on the same server, both chosen by the operating system.
        server = new SkillExchangeServer(Services.create(new Database(dir.resolve("test.db"))), 0, 0);
        server.start();
        address = URI.create("http://localhost:" + server.getHttpPort());
        tutorSide = RemoteApi.connect(address);
        learnerSide = RemoteApi.connect(address);
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
    void theStatusPageSaysWhatTheAddressIsFor() throws Exception {
        HttpResponse<String> page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(address.resolve("/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().startsWith("Skill Exchange server is running"), page.body());
        assertTrue(page.body().contains("--server="), page.body());
    }

    @Test
    void eachClientHasItsOwnSessionAndMustSignIn() throws Exception {
        assertTrue(learner.openSlots("", null).isEmpty());           // @Open: allowed
        assertThrows(AuthException.class, () -> learner.myBookings());

        tutor.register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        assertTrue(tutor.myListings().isEmpty(), "the tutor's session is signed in, so this call is allowed");
        assertThrows(AuthException.class, () -> learner.myBookings(), "the learner's session still is not");
    }

    @Test
    void exceptionsKeepTheirTypeAndFieldsOverHttp() throws Exception {
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
    void chatReachesTheOtherClientByLongPolling() throws Exception {
        BlockingQueue<Event> tutorEvents = new LinkedBlockingQueue<>();
        tutorSide.onEvent(tutorEvents::add);

        tutor.register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        Student asha = learner.register("Asha", "asha@test.edu", "pass1234".toCharArray());
        Listing guitar = tutor.createListing(ListingType.SWAP, "Guitar", "Chords", 60, 0, 2);
        TimeSlot slot = tutor.addSlot(guitar.getId(), LocalDateTime.now().plusDays(1), SessionMode.ONLINE, "https://meet.example.com/g");
        Booking booking = learner.book(slot.getId());
        assertEquals("https://meet.example.com/g", booking.getSlot().getPlace());

        Conversation chat = learner.openConversation(guitar.getId());
        learner.sendMessage(chat.id(), "Should I bring my own guitar?");
        Event.NewMessage pushed = null;
        Event e;
        while ((e = tutorEvents.poll(10, TimeUnit.SECONDS)) != null) {
            if (e instanceof Event.NewMessage m) {
                pushed = m;
                break;
            }
        }
        assertNotNull(pushed, "the tutor's waiting request should have been answered with the message");
        assertEquals("Should I bring my own guitar?", pushed.message().text());
        assertEquals(asha, pushed.message().sender());
    }

    @Test
    void socketAndHttpClientsShareOneServer() throws Exception {
        tutor.register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        Listing guitar = tutor.createListing(ListingType.FREE, "Guitar", "", 60, 0, 0);
        tutor.addSlot(guitar.getId(), LocalDateTime.now().plusDays(1), SessionMode.IN_PERSON, "Room 1");
        // A third client that comes in by socket sees what the HTTP client just created.
        try (RemoteApi bySocket = RemoteApi.connect("localhost", server.getPort())) {
            assertEquals(1, bySocket.api().openSlots("guitar", null).size());
            assertEquals(1, bySocket.api().stats().students());
        }
    }

    @Test
    void anAddressThatIsNotOurServerIsRefused() {
        IOException e = assertThrows(IOException.class, () -> RemoteApi.connect(URI.create("http://localhost:" + server.getHttpPort() + "/nothing-here/")));
        assertNotNull(e.getMessage());
    }

    @Test
    void aStoppedServerIsReportedAsALostConnection() throws Exception {
        learner.stats();
        server.close();
        assertThrows(ConnectionLostException.class, () -> learner.stats());
    }
}
