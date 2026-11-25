package skillexchange.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skillexchange.exception.ModuleFormatException;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.FillBlank;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.ModuleDraft;
import skillexchange.model.MultipleChoice;
import skillexchange.model.Passwords;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.model.TrueFalse;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesTest {

    @TempDir
    Path dir;

    private static List<ModuleDraft> parse(String text) throws Exception {
        return ModuleFileParser.parse(new BufferedReader(new StringReader(text)));
    }

    @Test
    void parsesAllThreeKindsOfQuestion() throws Exception {
        List<ModuleDraft> modules = parse("""
                # comment
                MODULE: Basics
                SUMMARY: First steps
                MCQ: Pick the keyword: one
                - var
                * def
                POINTS: 3
                TF: x == y compares => true
                FILL: After x = 5, x + 1 is ____. => 6 | six
                MODULE: Second
                TF: Easy. => false
                """);
        assertEquals(2, modules.size());
        ModuleDraft first = modules.get(0);
        assertEquals("First steps", first.summary());
        MultipleChoice mcq = (MultipleChoice) first.exercises().get(0);
        assertEquals("def", mcq.correctAnswer());
        assertEquals(3, mcq.points());
        assertTrue(((TrueFalse) first.exercises().get(1)).answer());
        assertEquals(List.of("6", "six"), ((FillBlank) first.exercises().get(2)).accepted());
        assertEquals("After x = 5, x + 1 is ____.", first.exercises().get(2).prompt());
    }

    @Test
    void reportsTheLineOfAMistake() {
        ModuleFormatException e = assertThrows(ModuleFormatException.class,
                () -> parse("MODULE: M\nMCQ: Q?\n- a\n- b\n"));
        assertEquals(2, e.getLine());
        assertEquals(3, assertThrows(ModuleFormatException.class, () -> parse("MODULE: M\n\nQUIZ: what\n")).getLine());
        assertThrows(ModuleFormatException.class, () -> parse("MCQ: no module first\n"));
        assertThrows(ModuleFormatException.class, () -> parse("MODULE: M\nTF: maybe => perhaps\n"));
    }

    @Test
    void theBundledDemoModulesAreValid() throws Exception {
        assertEquals(2, ModuleFileParser.parseResource("/modules/python-basics.txt").size());
        assertEquals(1, ModuleFileParser.parseResource("/modules/java-basics.txt").size());
    }

    private static Booking booking(Student learner, Listing listing) {
        TimeSlot slot = new TimeSlot(1, listing, LocalDateTime.of(2026, 12, 1, 10, 0), SessionMode.IN_PERSON,
                "Room \"B\", block 2", true);
        return new Booking(7, learner, slot, BookingStatus.CONFIRMED, 0, LocalDateTime.of(2026, 11, 25, 9, 0));
    }

    @Test
    void csvQuotesCommasAndQuotes() throws Exception {
        Student tutor = new Student(1, "Ravi", "ravi@test.edu");
        Student learner = new Student(2, "Asha", "asha@test.edu");
        Listing listing = Listing.of(ListingType.FREE, 1, tutor, "Guitar, beginner", "", 30, 0, 0);
        Path file = dir.resolve("sessions.csv");
        SessionCsv.write(file, learner, List.of(booking(learner, listing)));

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("7,learner,\"Guitar, beginner\",Ravi,FREE,Free,"), lines.get(1));
        assertTrue(lines.get(1).contains("\"Room \"\"B\"\", block 2\""), lines.get(1));
    }

    @Test
    void backupKeepsSharedReferencesAndDropsPasswordData() throws Exception {
        byte[] salt = Passwords.newSalt();
        Student tutor = new Student(1, "Ravi", "ravi@test.edu", salt, Passwords.hash("pass1234".toCharArray(), salt));
        assertTrue(tutor.checkPassword("pass1234".toCharArray()));
        Student learner = new Student(2, "Asha", "asha@test.edu");
        Listing listing = Listing.of(ListingType.SWAP, 1, tutor, "Guitar", "", 30, 0, 2);
        Path file = dir.resolve("backup.ser");
        Backup.write(file, new Backup.Snapshot(LocalDateTime.now(), List.of(tutor, learner), List.of(listing),
                List.of(booking(learner, listing))));

        Backup.Snapshot back = Backup.read(file);
        // One Student object before, one after: the tutor in the list IS the listing's tutor.
        assertSame(back.students().get(0), back.listings().get(0).getTutor());
        assertSame(back.students().get(1), back.bookings().get(0).getLearner());
        assertEquals(2, back.listings().get(0).getCreditCost());
        // The salt and hash are transient, so they were not written: the restored copy knows no password.
        assertFalse(back.students().get(0).checkPassword("pass1234".toCharArray()));
    }
}
