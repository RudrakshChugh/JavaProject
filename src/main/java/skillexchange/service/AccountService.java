package skillexchange.service;

import skillexchange.exception.AuthException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.exception.ValidationException;
import skillexchange.model.Passwords;
import skillexchange.model.Student;
import skillexchange.persistence.Database;
import skillexchange.persistence.StudentDao;
import skillexchange.validation.Validator;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Creating accounts and signing in. */
public final class AccountService {
    private static final Logger LOG = Logger.getLogger(AccountService.class.getName());

    public static final int STARTING_CREDITS = 5;
    private static final int MAX_WRONG_PASSWORDS = 5;
    private static final int LOCK_SECONDS = 30;

    /** Wrong passwords in a row for one email, and until when sign-in is paused. A nested record. */
    private record Attempts(int count, long lockedUntilMillis) { }

    private final Database db;
    private final AppClock clock;
    private final StudentDao students;
    private final CreditLedger ledger;
    // Many client threads sign in at the same time, so this map must be safe to share.
    private final Map<String, Attempts> wrongPasswords = new ConcurrentHashMap<>();

    AccountService(Database db, AppClock clock, StudentDao students, CreditLedger ledger) {
        this.db = db;
        this.clock = clock;
        this.students = students;
        this.ledger = ledger;
    }

    /**
     * Creates an account. Synchronized so that two people registering the same email at the same
     * moment cannot both pass the "is this email taken?" check; the UNIQUE column backs that up.
     */
    public synchronized Student register(String name, String email, char[] password) throws SkillExchangeException {
        String cleanName = Validator.name(name);
        String cleanEmail = Validator.email(email);
        Validator.password(password);
        byte[] salt = Passwords.newSalt();
        byte[] hash;
        try {
            hash = Passwords.hash(password, salt);
        } finally {
            Arrays.fill(password, '\0');    // do not leave the password lying in memory
        }
        LocalDateTime now = clock.now();
        Student created = db.inTransaction(c -> {
            if (students.emailExists(c, cleanEmail))
                throw new ValidationException("email", "An account with this email already exists. Sign in instead.");
            long id = students.insert(c, cleanName, cleanEmail, salt, hash, 0, now);
            ledger.open(c, id, STARTING_CREDITS, now);      // same transaction: no account without its balance
            return students.require(c, id);
        });
        LOG.info(() -> "Registered " + created.getName() + " (student " + created.getId() + ")");
        return created;
    }

    /** Returns the student if the email and password match. Five wrong passwords pause that email for 30 seconds. */
    public Student login(String email, char[] password) throws AuthException {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Attempts attempts = wrongPasswords.get(key);
        if (attempts != null && attempts.lockedUntilMillis() > now) {
            long wait = (attempts.lockedUntilMillis() - now + 999) / 1000;
            throw new AuthException("Too many wrong passwords. Try again in " + wait + (wait == 1 ? " second." : " seconds."));
        }
        Optional<Student> found = db.query(c -> students.findByEmailWithPassword(c, key));
        boolean ok;
        try {
            ok = found.isPresent() && found.get().checkPassword(password);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        if (!ok) {
            // merge() updates the counter atomically, even if two wrong attempts arrive together.
            Attempts updated = wrongPasswords.merge(key, new Attempts(1, 0),
                    (old, one) -> new Attempts(old.count() + 1, 0));
            if (updated.count() >= MAX_WRONG_PASSWORDS) {
                wrongPasswords.put(key, new Attempts(0, now + LOCK_SECONDS * 1000L));
                LOG.warning(() -> "Sign-in paused for " + LOCK_SECONDS + " s after repeated wrong passwords: " + key);
            }
            // The same answer for an unknown email and a wrong password, so nobody can test which emails exist.
            throw new AuthException("Wrong email or password");
        }
        wrongPasswords.remove(key);
        Student s = found.get();
        LOG.info(() -> s.getName() + " signed in");
        return new Student(s.getId(), s.getName(), s.getEmail());   // a copy without the hash
    }

    /** Forgets pauses that have run out. Called once a minute by the server's housekeeping thread. */
    public int forgetExpiredLocks() {
        long now = System.currentTimeMillis();
        int before = wrongPasswords.size();
        wrongPasswords.values().removeIf(a -> a.lockedUntilMillis() != 0 && a.lockedUntilMillis() < now);
        return before - wrongPasswords.size();
    }

    public int countStudents() {
        return db.query(students::count);
    }
}
