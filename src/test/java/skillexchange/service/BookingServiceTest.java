package skillexchange.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skillexchange.exception.AuthException;
import skillexchange.exception.BookingConflictException;
import skillexchange.exception.BookingException;
import skillexchange.exception.InsufficientCreditsException;
import skillexchange.exception.SlotAlreadyBookedException;
import skillexchange.exception.ValidationException;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.CreditTransaction;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.persistence.Database;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The booking rules and the escrow ledger, against a real SQLite database in a temporary folder. */
class BookingServiceTest {

    @TempDir
    Path dir;

    private Services s;
    private Student asha;
    private Student ravi;
    private Student meera;
    private LocalDateTime tomorrow;

    @BeforeEach
    void setUp() throws Exception {
        s = Services.create(new Database(dir.resolve("test.db")));
        asha = s.accounts().register("Asha", "asha@test.edu", "pass1234".toCharArray());
        ravi = s.accounts().register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        meera = s.accounts().register("Meera", "meera@test.edu", "pass1234".toCharArray());
        tomorrow = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private Listing swap(Student tutor, int credits) throws Exception {
        return s.bookings().createListing(tutor, ListingType.SWAP, "Guitar", "Chords", 60, 0, credits);
    }

    private TimeSlot slot(Student tutor, Listing listing, LocalDateTime start) throws Exception {
        return s.bookings().addSlot(tutor, listing.getId(), start, SessionMode.IN_PERSON, "Room 1");
    }

    @Test
    void newStudentsStartWithFiveCredits() {
        assertEquals(5, s.bookings().credits(asha).balance());
        List<CreditTransaction> history = s.bookings().credits(asha).history();
        assertEquals(1, history.size());
        assertEquals(CreditTransaction.Type.INITIAL, history.get(0).type());
    }

    @Test
    void emailsMustBeUniqueWhateverTheCase() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> s.accounts().register("Other Asha", "ASHA@test.edu", "pass1234".toCharArray()));
        assertEquals("email", e.getField());
    }

    @Test
    void signInChecksThePassword() throws Exception {
        assertEquals(asha, s.accounts().login("Asha@Test.edu", "pass1234".toCharArray()));
        assertThrows(AuthException.class, () -> s.accounts().login("asha@test.edu", "wrong1234".toCharArray()));
        assertThrows(AuthException.class, () -> s.accounts().login("nobody@test.edu", "pass1234".toCharArray()));
    }

    @Test
    void fiveWrongPasswordsPauseSignIn() {
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthException.class, () -> s.accounts().login("ravi@test.edu", "wrong1234".toCharArray()));
        }
        AuthException e = assertThrows(AuthException.class, () -> s.accounts().login("ravi@test.edu", "pass1234".toCharArray()));
        assertTrue(e.getMessage().startsWith("Too many wrong passwords"), e.getMessage());
    }

    @Test
    void bookingASwapSessionHoldsCreditsAndCompletingPaysTheTutor() throws Exception {
        TimeSlot t = slot(ravi, swap(ravi, 2), tomorrow);
        Booking b = s.bookings().book(asha, t.getId());

        assertEquals(BookingStatus.CONFIRMED, b.getStatus());
        assertEquals(3, s.bookings().credits(asha).balance());
        assertEquals(2, s.bookings().credits(asha).held());
        assertEquals(5, s.bookings().credits(ravi).balance());      // not paid yet: the credits are in escrow

        s.clock().set(Clock.fixed(tomorrow.plusHours(2).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        s.bookings().complete(asha, b.getId());
        assertEquals(7, s.bookings().credits(ravi).balance());
        assertEquals(0, s.bookings().credits(asha).held());
        assertEquals(BookingStatus.COMPLETED, s.bookings().findBooking(b.getId()).orElseThrow().getStatus());
    }

    @Test
    void cancellingRefundsAndReopensTheSlot() throws Exception {
        TimeSlot t = slot(ravi, swap(ravi, 2), tomorrow);
        Booking b = s.bookings().book(asha, t.getId());
        s.bookings().cancel(ravi, b.getId());                       // the tutor may cancel too

        assertEquals(5, s.bookings().credits(asha).balance());
        assertEquals(1, s.bookings().openSlots("", null).size());
        s.bookings().book(meera, t.getId());                        // and someone else can take it
    }

    @Test
    void aSlotCannotBeBookedTwice() throws Exception {
        TimeSlot t = slot(ravi, swap(ravi, 1), tomorrow);
        s.bookings().book(asha, t.getId());
        assertThrows(SlotAlreadyBookedException.class, () -> s.bookings().book(meera, t.getId()));
    }

    @Test
    void aFailedBookingIsRolledBackCompletely() throws Exception {
        TimeSlot t = slot(ravi, swap(ravi, 9), tomorrow);            // costs more than Asha's 5 credits
        InsufficientCreditsException e = assertThrows(InsufficientCreditsException.class,
                () -> s.bookings().book(asha, t.getId()));
        assertEquals(9, e.getRequired());
        assertEquals(5, e.getAvailable());

        // The booking row was inserted before the credit check failed; the rollback must have removed it.
        assertTrue(s.bookings().bookingsOf(asha).isEmpty());
        assertEquals(5, s.bookings().credits(asha).balance());
        assertFalse(s.bookings().openSlots("", null).get(0).isBooked());
    }

    @Test
    void nobodyCanBeInTwoPlacesAtOnce() throws Exception {
        TimeSlot guitar = slot(ravi, swap(ravi, 1), tomorrow);
        Listing maths = s.bookings().createListing(meera, ListingType.FREE, "Maths", "", 60, 0, 0);
        TimeSlot overlapping = slot(meera, maths, tomorrow.plusMinutes(30));

        assertThrows(BookingConflictException.class, () -> s.bookings().book(ravi, guitar.getId()), "own session");
        s.bookings().book(asha, guitar.getId());
        assertThrows(BookingConflictException.class, () -> s.bookings().book(asha, overlapping.getId()), "learner busy");
        assertThrows(BookingConflictException.class, () -> s.bookings().book(ravi, overlapping.getId()), "teaching then");
        assertThrows(BookingConflictException.class, () -> slot(ravi, swap(ravi, 1), tomorrow.plusMinutes(10)), "tutor slots overlap");
    }

    @Test
    void onlyTheRightPeopleMayActOnABooking() throws Exception {
        TimeSlot t = slot(ravi, swap(ravi, 1), tomorrow);
        Booking b = s.bookings().book(asha, t.getId());
        assertThrows(BookingException.class, () -> s.bookings().cancel(meera, b.getId()));
        assertThrows(BookingException.class, () -> s.bookings().complete(ravi, b.getId()), "only the learner confirms");
        assertThrows(BookingException.class, () -> s.bookings().complete(asha, b.getId()), "not before it starts");
        assertThrows(BookingException.class, () -> s.bookings().removeSlot(ravi, t.getId()), "booked slot");
    }

    @Test
    void deletingAListingNeedsNoConfirmedBookings() throws Exception {
        Listing guitar = swap(ravi, 1);
        TimeSlot t = slot(ravi, guitar, tomorrow);
        slot(ravi, guitar, tomorrow.plusDays(1));
        Booking b = s.bookings().book(asha, t.getId());
        assertThrows(BookingException.class, () -> s.bookings().deleteListing(ravi, guitar.getId()));
        assertThrows(BookingException.class, () -> s.bookings().deleteListing(asha, guitar.getId()), "not the owner");

        s.bookings().cancel(asha, b.getId());
        s.bookings().deleteListing(ravi, guitar.getId());
        assertTrue(s.bookings().openSlots("", null).isEmpty());
        assertTrue(s.bookings().listingsOf(ravi).isEmpty());
        assertEquals(1, s.bookings().bookingsOf(asha).size(), "history is kept");
    }

    @Test
    void searchMatchesSkillTutorAndType() throws Exception {
        slot(ravi, swap(ravi, 1), tomorrow);
        Listing maths = s.bookings().createListing(meera, ListingType.FREE, "Maths 100%", "", 60, 0, 0);
        slot(meera, maths, tomorrow);

        assertEquals(2, s.bookings().openSlots("", null).size());
        assertEquals(1, s.bookings().openSlots("guit", null).size());
        assertEquals(1, s.bookings().openSlots("MEERA", null).size());
        assertEquals(1, s.bookings().openSlots("", ListingType.FREE).size());
        assertEquals(1, s.bookings().openSlots("100%", null).size(), "% is searched for literally");
        assertEquals(0, s.bookings().openSlots("' OR 1=1 --", null).size(), "SQL in the search box is just text");
    }

    @Test
    void placeIsStoredWithTheSlot() throws Exception {
        TimeSlot t = s.bookings().addSlot(ravi, swap(ravi, 1).getId(), tomorrow, SessionMode.ONLINE, "https://meet.example.com/g");
        assertEquals("https://meet.example.com/g", s.bookings().slotsOf(ravi).get(0).getPlace());
        assertNull(t.withoutPlace().getPlace());
    }
}
