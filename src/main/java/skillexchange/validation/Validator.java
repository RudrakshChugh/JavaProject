package skillexchange.validation;

import skillexchange.exception.ValidationException;
import skillexchange.model.Exercise;
import skillexchange.model.FillBlank;
import skillexchange.model.MultipleChoice;
import skillexchange.model.Passwords;
import skillexchange.model.SessionMode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every rule about what a user may type, in one place.
 *
 * The JavaFX forms call these methods while the user types, to show a message under the field.
 * The server calls the very same methods again before it stores anything, because a request does
 * not have to come from our own client. Each method returns the cleaned-up value, or throws a
 * {@link ValidationException} naming the field.
 */
public final class Validator {

    public static final int MAX_NAME = 40;
    public static final int MAX_EMAIL = 254;
    public static final int MAX_SKILL = 40;
    public static final int MAX_DESCRIPTION = 300;
    public static final int MIN_DURATION = 15;
    public static final int MAX_DURATION = 240;
    public static final double MAX_FEE = 100_000;
    public static final int MAX_CREDIT_COST = 10;
    public static final int MAX_PLACE = 120;
    public static final int MAX_MESSAGE = 1000;
    public static final int MAX_TITLE = 60;
    public static final int MAX_SUMMARY = 200;
    public static final int MAX_PROMPT = 300;
    public static final int MAX_EXERCISES = 30;
    public static final int MAX_OPTIONS = 6;

    // One or more labels ending in a dot, then a top-level domain of letters: "gmail.com", "thapar.edu".
    // A label starts and ends with a letter or digit and may have hyphens in between.
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9._%+-]{1,64}@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$");

    // Starts with a letter (any language), then letters, spaces, dots, apostrophes or hyphens.
    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L} .'-]*$");

    private Validator() { }

    // ---------- Account ----------

    public static String name(String raw) throws ValidationException {
        String v = clean(raw);
        require(!v.isEmpty(), "name", "Enter your name");
        require(v.length() >= 2 && v.length() <= MAX_NAME, "name", "Name must be 2 to " + MAX_NAME + " characters");
        require(NAME.matcher(v).matches(), "name", "Name can only have letters, spaces, dots, hyphens and apostrophes");
        return v;
    }

    /** Checks the format of an email address and returns it in lower case. */
    public static String email(String raw) throws ValidationException {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        require(!v.isEmpty(), "email", "Enter your email address");
        require(v.length() <= MAX_EMAIL, "email", "That email address is too long");
        require(v.chars().noneMatch(Character::isWhitespace), "email", "An email address cannot contain spaces");
        int at = v.indexOf('@');
        require(at > 0 && at == v.lastIndexOf('@'), "email", "An email address has exactly one @, like name@example.com");
        String local = v.substring(0, at);
        String domain = v.substring(at + 1);
        require(!local.startsWith(".") && !local.endsWith(".") && !v.contains(".."),
                "email", "Dots cannot start, end or repeat in an email address");
        require(domain.contains("."), "email", "The part after @ needs a domain, like gmail.com");
        require(EMAIL.matcher(v).matches(), "email", "Enter a valid email address, like name@example.com");
        return v;
    }

    public static char[] password(char[] raw) throws ValidationException {
        require(raw != null && raw.length > 0, "password", "Enter a password");
        require(raw.length >= Passwords.MIN_LENGTH, "password",
                "Password must be at least " + Passwords.MIN_LENGTH + " characters");
        require(raw.length <= Passwords.MAX_LENGTH, "password",
                "Password must be at most " + Passwords.MAX_LENGTH + " characters");
        boolean letter = false;
        boolean digit = false;
        for (char ch : raw) {
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
        }
        require(letter && digit, "password", "Password needs at least one letter and one number");
        return raw;
    }

    // ---------- Listings and slots ----------

    public static String skill(String raw) throws ValidationException {
        String v = clean(raw);
        require(!v.isEmpty(), "skill", "Enter the skill you will teach");
        require(v.length() >= 2 && v.length() <= MAX_SKILL, "skill", "Skill must be 2 to " + MAX_SKILL + " characters");
        return v;
    }

    public static String description(String raw) throws ValidationException {
        String v = clean(raw);
        require(v.length() <= MAX_DESCRIPTION, "description", "Description must be at most " + MAX_DESCRIPTION + " characters");
        return v;
    }

    public static int duration(int minutes) throws ValidationException {
        require(minutes >= MIN_DURATION && minutes <= MAX_DURATION, "duration",
                "A session lasts " + MIN_DURATION + " to " + MAX_DURATION + " minutes");
        return minutes;
    }

    public static double fee(double rupees) throws ValidationException {
        // "!(x > 0)" is also true for NaN, so NaN is rejected here.
        require(rupees > 0 && rupees <= MAX_FEE, "fee", "Fee must be between Rs. 1 and Rs. " + (long) MAX_FEE);
        return Math.round(rupees * 100) / 100.0;
    }

    public static int creditCost(int credits) throws ValidationException {
        require(credits >= 1 && credits <= MAX_CREDIT_COST, "credits", "A session costs 1 to " + MAX_CREDIT_COST + " credits");
        return credits;
    }

    public static LocalDateTime slotStart(LocalDateTime start, LocalDateTime now) throws ValidationException {
        require(start != null, "start", "Choose a date and a time");
        require(start.isAfter(now), "start", "The session must start in the future");
        require(start.isBefore(now.plusYears(1)), "start", "The session must start within a year from now");
        return start.withSecond(0).withNano(0);
    }

    /** A meeting link for an online session, a room or place for a session in person. */
    public static String place(SessionMode mode, String raw) throws ValidationException {
        require(mode != null, "mode", "Choose online or in person");
        String v = clean(raw);
        require(v.length() <= MAX_PLACE, "place", mode.getPlaceLabel() + " must be at most " + MAX_PLACE + " characters");
        if (mode == SessionMode.ONLINE) {
            require(!v.isEmpty(), "place", "Enter the meeting link");
            require(isWebLink(v), "place", "The meeting link must start with https:// or http://");
        } else {
            require(v.length() >= 2, "place", "Enter the room or place where you will meet");
        }
        return v;
    }

    private static boolean isWebLink(String v) {
        try {
            URI uri = new URI(v);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ---------- Chat ----------

    public static String message(String raw) throws ValidationException {
        String v = raw == null ? "" : raw.strip();
        require(!v.isEmpty(), "message", "Type a message first");
        require(v.length() <= MAX_MESSAGE, "message", "A message can be at most " + MAX_MESSAGE + " characters");
        return v;
    }

    // ---------- Learning modules ----------

    public static String moduleTitle(String raw) throws ValidationException {
        String v = clean(raw);
        require(v.length() >= 2 && v.length() <= MAX_TITLE, "title", "Module title must be 2 to " + MAX_TITLE + " characters");
        return v;
    }

    public static String moduleSummary(String raw) throws ValidationException {
        String v = clean(raw);
        require(v.length() <= MAX_SUMMARY, "summary", "Summary must be at most " + MAX_SUMMARY + " characters");
        return v;
    }

    public static List<Exercise> exercises(List<Exercise> list) throws ValidationException {
        require(list != null && !list.isEmpty(), "exercises", "A module needs at least one exercise");
        require(list.size() <= MAX_EXERCISES, "exercises", "A module can have at most " + MAX_EXERCISES + " exercises");
        int number = 1;
        for (Exercise e : list) {
            exercise(e, "Exercise " + number++);
        }
        return list;
    }

    private static void exercise(Exercise e, String label) throws ValidationException {
        require(e != null, "exercises", label + " is missing");
        String prompt = clean(e.prompt());
        require(prompt.length() >= 5 && prompt.length() <= MAX_PROMPT, "exercises",
                label + ": the question must be 5 to " + MAX_PROMPT + " characters");
        require(e.points() >= 1 && e.points() <= Exercise.MAX_POINTS, "exercises",
                label + ": points must be 1 to " + Exercise.MAX_POINTS);
        // Pattern matching for instanceof: the test and the cast are one step.
        if (e instanceof MultipleChoice mcq) {
            require(mcq.options().size() <= MAX_OPTIONS, "exercises", label + ": at most " + MAX_OPTIONS + " options");
            Set<String> seen = new HashSet<>();
            for (String option : mcq.options()) {
                require(!clean(option).isEmpty(), "exercises", label + ": an option is empty");
                require(seen.add(Exercise.normalise(option)), "exercises", label + ": two options are the same");
            }
        } else if (e instanceof FillBlank fill) {
            for (String answer : fill.accepted()) {
                require(!clean(answer).isEmpty() && answer.length() <= 60, "exercises",
                        label + ": an accepted answer must be 1 to 60 characters");
            }
        }
    }

    // ---------- Numbers typed into text fields ----------

    public static int wholeNumber(String field, String label, String raw) throws ValidationException {
        try {
            return Integer.parseInt(clean(raw));
        } catch (NumberFormatException e) {
            throw new ValidationException(field, label + " must be a whole number");
        }
    }

    public static double amount(String field, String label, String raw) throws ValidationException {
        try {
            return Double.parseDouble(clean(raw));
        } catch (NumberFormatException e) {
            throw new ValidationException(field, label + " must be a number");
        }
    }

    // ---------- Helpers ----------

    /** A check that may fail. A functional interface, so a form can pass one as a lambda. */
    @FunctionalInterface
    public interface Check {
        void run() throws ValidationException;
    }

    /** Runs a check and returns its message if it failed, or an empty Optional if the value is fine. */
    public static Optional<String> errorOf(Check check) {
        try {
            check.run();
            return Optional.empty();
        } catch (ValidationException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Trims the ends and turns any run of spaces, tabs or line breaks into one space. */
    private static String clean(String raw) {
        return raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    }

    private static void require(boolean ok, String field, String message) throws ValidationException {
        if (!ok) throw new ValidationException(field, message);
    }
}
