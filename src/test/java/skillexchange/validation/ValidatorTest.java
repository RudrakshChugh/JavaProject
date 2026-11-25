package skillexchange.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import skillexchange.exception.ValidationException;
import skillexchange.model.SessionMode;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"asha@uni.edu", "first.last@thapar.edu", "a_b+tag@mail.co.in", "x@sub.domain.org"})
    void acceptsRealisticEmails(String email) throws ValidationException {
        assertEquals(email, Validator.email(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "plain", "@nouser.com", "two@@at.com", "a@b", "a@b.", "a@.com", "a b@c.com",
            ".dot@start.com", "dot.@end.com", "dou..ble@dots.com", "a@-dash.com", "a@dash-.com", "a@b.c", "a@b.c0m"})
    void rejectsBadEmails(String email) {
        ValidationException e = assertThrows(ValidationException.class, () -> Validator.email(email));
        assertEquals("email", e.getField());
    }

    @Test
    void emailIsTrimmedAndLowerCased() throws ValidationException {
        assertEquals("asha@uni.edu", Validator.email("  Asha@Uni.EDU "));
    }

    @Test
    void passwordNeedsLengthLetterAndDigit() {
        assertThrows(ValidationException.class, () -> Validator.password("short1".toCharArray()));
        assertThrows(ValidationException.class, () -> Validator.password("onlyletters".toCharArray()));
        assertThrows(ValidationException.class, () -> Validator.password("1234567890".toCharArray()));
        assertTrue(Validator.errorOf(() -> Validator.password("demo1234".toCharArray())).isEmpty());
    }

    @Test
    void nameRules() throws ValidationException {
        assertEquals("Asha Rao", Validator.name("  Asha   Rao "));
        assertEquals("O'Neil-Smith Jr.", Validator.name("O'Neil-Smith Jr."));
        assertThrows(ValidationException.class, () -> Validator.name("A"));
        assertThrows(ValidationException.class, () -> Validator.name("R2D2"));
        assertThrows(ValidationException.class, () -> Validator.name("<script>"));
    }

    @Test
    void onlineSessionsNeedALink() throws ValidationException {
        assertEquals("https://meet.example.com/x", Validator.place(SessionMode.ONLINE, "https://meet.example.com/x"));
        assertThrows(ValidationException.class, () -> Validator.place(SessionMode.ONLINE, "Room 4"));
        assertThrows(ValidationException.class, () -> Validator.place(SessionMode.ONLINE, "ftp://files.example.com"));
        assertEquals("Library, room 2", Validator.place(SessionMode.IN_PERSON, "Library, room 2"));
        assertThrows(ValidationException.class, () -> Validator.place(SessionMode.IN_PERSON, ""));
    }

    @Test
    void slotsMustBeInTheFuture() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 10, 0);
        assertThrows(ValidationException.class, () -> Validator.slotStart(now.minusMinutes(1), now));
        assertThrows(ValidationException.class, () -> Validator.slotStart(now.plusYears(2), now));
        assertTrue(Validator.errorOf(() -> Validator.slotStart(now.plusDays(1), now)).isEmpty());
    }

    @Test
    void numbersHaveRanges() {
        assertThrows(ValidationException.class, () -> Validator.fee(0));
        assertThrows(ValidationException.class, () -> Validator.fee(Double.NaN));
        assertThrows(ValidationException.class, () -> Validator.creditCost(11));
        assertThrows(ValidationException.class, () -> Validator.duration(5));
        assertThrows(ValidationException.class, () -> Validator.wholeNumber("credits", "Credits", "two"));
    }
}
