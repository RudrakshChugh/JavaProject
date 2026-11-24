package skillexchange.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import skillexchange.Main;
import skillexchange.exception.BookingException;
import skillexchange.exception.PersistenceException;
import skillexchange.model.*;
import skillexchange.persistence.DataStore;
import skillexchange.service.BookingService;
import skillexchange.service.CreditLedger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static skillexchange.web.Json.obj;

/**
 * Web front end. Serves the static site in web/ and a small JSON API over BookingService,
 * using only the JDK's built-in HttpServer. Like ConsoleApp, it holds no business rules.
 * Requests run on a thread pool, so the synchronized service is what keeps concurrent bookings safe.
 *
 * Signing in: POST /api/login checks the password and answers with a session cookie. Every request
 * that acts for a student (booking, cancelling, teaching) reads the student from that cookie, never
 * from a parameter, so one student cannot act as another.
 *
 *   java -cp out skillexchange.web.WebServer            start on port 8080
 *   java -cp out skillexchange.web.WebServer 9090       another port
 *   java -cp out skillexchange.web.WebServer --reset    delete saved data and start with demo data
 */
public class WebServer {
    private static final int STARTING_CREDITS = 5;
    // The same limits as the form fields in web/app.js. The browser's limits are a convenience;
    // these are the ones that count, because a request does not have to come from the form.
    private static final int MAX_NAME = 40;
    private static final int MAX_EMAIL = 60;
    private static final int MAX_SKILL = 60;
    private static final int MAX_DESCRIPTION = 120;
    private static final double MAX_FEE = 100_000;
    private static final int MAX_DAYS_AHEAD = 365;   // how far in the future a slot may be published
    private static final int RECENT_BOOKINGS = 10;

    private static final String SESSION_COOKIE = "skx_session";
    private static final int SESSION_SECONDS = 7 * 24 * 60 * 60;
    private static final int MAX_WRONG_PASSWORDS = 5;   // in a row, before sign-in pauses for that profile
    private static final int LOCK_SECONDS = 30;

    // This server only answers on the local machine, so these are the only names a browser can reach it by.
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png",
            "ico", "image/x-icon", "json", "application/json; charset=utf-8");

    private final BookingService service;
    private final CreditLedger ledger;
    private final DataStore store;
    private final Path webRoot;

    /** Who is signed in. Kept in memory only, so restarting the server signs everyone out. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> wrongPasswords = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record Session(String studentId, long expiresAtMillis) { }

    private record Attempts(int count, long lockedUntilMillis) { }

    WebServer(BookingService service, DataStore store, Path webRoot) {
        this.service = service;
        this.ledger = service.getLedger();
        this.store = store;
        this.webRoot = webRoot;
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        boolean reset = false;
        for (String a : args) {
            if (a.equals("--reset")) {
                reset = true;
                continue;
            }
            try {
                port = Integer.parseInt(a);
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 1 || port > 65535) {
                System.out.println("ERROR: \"" + a + "\" is not a valid port. Usage: WebServer [port] [--reset]");
                return;
            }
        }
        Path webRoot = Path.of("web").toAbsolutePath().normalize();
        if (!Files.isDirectory(webRoot)) {
            System.out.println("ERROR: web/ folder not found. Run this from the project root.");
            return;
        }
        DataStore store = new DataStore(Path.of("data"));
        if (reset) {
            store.reset();
            System.out.println("Saved data cleared.");
        }
        BookingService service;
        try {
            service = Main.loadOrSeed(store);
        } catch (PersistenceException e) {
            System.out.println("ERROR: " + e.getMessage());
            System.out.println("Run with --reset to start fresh (this deletes saved data).");
            return;
        }
        if (service.getAllStudents().stream().noneMatch(Student::hasPassword)) {
            System.out.println("NOTE: the saved data is from before passwords existed, so nobody can sign in.");
            System.out.println("Run with --reset to start fresh with demo data (this deletes saved data).");
        }
        try {
            new WebServer(service, store, webRoot).start(port);
        } catch (BindException e) {
            System.out.println("ERROR: port " + port + " is already in use. Is the app already running?");
            System.out.println("Stop the other copy, or pick another port: WebServer " + (port + 1));
        }
    }

    void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(16));   // many requests at once
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveWithReports));
        System.out.println("Skill Exchange is running at http://localhost:" + port + "   (Ctrl+C to stop)");
    }

    // ---------- API ----------

    private void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        Object body;
        try {
            String path = ex.getRequestURI().getPath().substring("/api/".length());
            checkOrigin(ex);
            body = route(ex, ex.getRequestMethod(), path, params(ex));
        } catch (ApiException e) {
            status = e.status;
            body = error(e.type, e.getMessage());
        } catch (BookingException e) {
            status = 409;
            body = error(e.getClass().getSimpleName(), e.getMessage());
        } catch (DateTimeParseException e) {
            status = 400;
            body = error("DateTimeParseException", "Enter a valid date and time");
        } catch (IllegalArgumentException e) {
            status = 400;
            body = error(e.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            status = 500;
            body = error(e.getClass().getSimpleName(), "Something went wrong on the server");
            e.printStackTrace();
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        send(ex, status, bytes);
    }

    /**
     * Refuses requests that another web site is trying to send through the visitor's browser.
     *
     * 1. The Host header must be one of this machine's own names. A page on another site that has
     *    pointed its own domain at 127.0.0.1 ("DNS rebinding") still sends its own domain as Host.
     * 2. A browser adds an Origin header to every POST. It must name this same server.
     * Requests without an Origin (curl, or a plain GET) pass the second check.
     */
    private static void checkOrigin(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !LOCAL_HOSTS.contains(hostName(host)))
            throw new ApiException(403, "Forbidden", "This server only answers to localhost");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && ex.getRequestMethod().equals("POST") && !origin.equals("http://" + host))
            throw new ApiException(403, "Forbidden", "Requests from other sites are not allowed");
    }

    /** "localhost:8080" -> "localhost", "[::1]:8080" -> "[::1]". */
    private static String hostName(String host) {
        int colon = host.lastIndexOf(':');
        boolean hasPort = colon > host.lastIndexOf(']');
        return (hasPort ? host.substring(0, colon) : host).toLowerCase(Locale.ROOT);
    }

    private Object route(HttpExchange ex, String method, String path, Map<String, String> p) throws Exception {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        if (!get && !post) throw new ApiException(405, "MethodNotAllowed", "The API only accepts GET and POST");
        String[] s = path.split("/");

        // Open to everyone
        if (get && path.equals("stats")) return stats();
        if (get && path.equals("activity")) return activity();
        if (get && path.equals("students")) return service.getAllStudents().stream().map(WebServer::person).toList();
        if (get && path.equals("slots")) return openSlots(p.getOrDefault("q", ""), p.getOrDefault("type", "ALL"));
        if (get && s.length == 3 && s[0].equals("students") && s[2].equals("profile")) {
            Student st = service.findStudent(s[1]);
            if (st == null) throw new ApiException(404, "NotFound", "No student with id " + s[1]);
            return profileOf(st);
        }
        if (post && path.equals("demo/race")) return RaceDemo.run();

        // Signing in and out
        if (get && path.equals("me")) {
            Student st = signedIn(ex);
            return obj("student", st == null ? null : studentView(st));
        }
        if (post && path.equals("login")) return login(ex, p);
        if (post && path.equals("logout")) return logout(ex);
        if (post && path.equals("students")) return register(ex, p);

        // Everything below acts for the signed-in student
        if (get && path.equals("me/bookings")) return bookingsOf(requireSignedIn(ex));
        if (get && path.equals("me/ledger")) return ledgerOf(requireSignedIn(ex));
        if (get && path.equals("me/listings")) return listingsOf(requireSignedIn(ex));
        if (post && path.equals("slots")) return addSlot(requireSignedIn(ex), p);
        if (post && path.equals("listings")) return createListing(requireSignedIn(ex), p);
        if (post && path.equals("bookings")) return book(requireSignedIn(ex), p);
        if (post && s.length == 3 && s[2].equals("delete") && (s[0].equals("slots") || s[0].equals("listings"))) {
            Student tutor = requireSignedIn(ex);
            if (s[0].equals("slots")) service.removeSlot(tutor, s[1]);
            else service.removeListing(tutor, s[1]);
            save();
            return obj("ok", true);
        }
        if (post && s.length == 3 && s[0].equals("bookings") && (s[2].equals("cancel") || s[2].equals("complete"))) {
            Student actor = requireSignedIn(ex);
            if (s[2].equals("cancel")) service.cancel(actor, s[1]);
            else service.completeSession(actor, s[1]);
            save();
            return obj("ok", true);
        }
        throw new ApiException(404, "NotFound", "No such endpoint: " + method + " /api/" + path);
    }

    // ---------- Sessions ----------

    /** The student this request's session cookie belongs to, or null if nobody is signed in. */
    private Student signedIn(HttpExchange ex) {
        String token = sessionToken(ex);
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expiresAtMillis() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return service.findStudent(session.studentId());
    }

    private Student requireSignedIn(HttpExchange ex) {
        Student st = signedIn(ex);
        if (st == null) throw new ApiException(401, "Unauthorized", "Sign in first");
        return st;
    }

    private static String sessionToken(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(SESSION_COOKIE)) return kv[1];
            }
        }
        return null;
    }

    /** Gives the browser a long random token that stands for this student until they sign out. */
    private void startSession(HttpExchange ex, Student st) {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(old -> old.expiresAtMillis() < now);   // tidy up while we are here
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(token, new Session(st.getId(), now + SESSION_SECONDS * 1000L));
        setSessionCookie(ex, token, SESSION_SECONDS);
    }

    /**
     * HttpOnly: page scripts cannot read the token. SameSite=Strict: the browser never sends it
     * along with a request that started on another site.
     */
    private static void setSessionCookie(HttpExchange ex, String token, int maxAgeSeconds) {
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token
                + "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Strict");
    }

    private Object login(HttpExchange ex, Map<String, String> p) {
        String id = required(p, "studentId").toUpperCase(Locale.ROOT);
        String password = p.getOrDefault("password", "");
        long now = System.currentTimeMillis();
        Attempts attempts = wrongPasswords.get(id);
        if (attempts != null && attempts.lockedUntilMillis() > now) {
            long wait = (attempts.lockedUntilMillis() - now + 999) / 1000;
            throw new ApiException(429, "TooManyAttempts",
                    "Too many wrong passwords. Try again in " + wait + (wait == 1 ? " second" : " seconds"));
        }
        Student st = service.findStudent(id);
        if (st != null && !st.hasPassword())
            throw new ApiException(403, "Forbidden", "This profile was saved before passwords existed and cannot sign in. "
                    + "Restart the server with --reset to start fresh.");
        // The same answer for an unknown profile and a wrong password.
        if (st == null || !st.checkPassword(password.toCharArray())) {
            int count = (attempts == null ? 0 : attempts.count()) + 1;
            wrongPasswords.put(id, count >= MAX_WRONG_PASSWORDS
                    ? new Attempts(0, now + LOCK_SECONDS * 1000L)
                    : new Attempts(count, 0));
            throw new ApiException(401, "WrongPassword", "Wrong password");
        }
        wrongPasswords.remove(id);
        startSession(ex, st);
        return studentView(st);
    }

    private Object logout(HttpExchange ex) {
        String token = sessionToken(ex);
        if (token != null) sessions.remove(token);
        setSessionCookie(ex, "", 0);   // Max-Age=0 tells the browser to drop it
        return obj("ok", true);
    }

    private Object register(HttpExchange ex, Map<String, String> p) {
        String name = text(p, "name", "Name", MAX_NAME, true);
        String email = text(p, "email", "Email", MAX_EMAIL, false);
        if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw new IllegalArgumentException("Enter a valid email address, or leave it empty");
        char[] password = p.getOrDefault("password", "").toCharArray();   // the Student constructor checks its length
        Student st;
        // Hold the service's lock so no other request can take the same new id in between.
        synchronized (service) {
            st = new Student(service.nextStudentId(), name, email.isEmpty() ? null : email, password);
            service.registerStudent(st, STARTING_CREDITS);
        }
        save();
        startSession(ex, st);
        return studentView(st);
    }

    // ---------- Reading ----------

    private Object stats() {
        List<Booking> all = service.getAllBookings();
        long completed = all.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        return obj("students", service.getAllStudents().size(),
                "listings", service.getAllListings().size(),
                "openSlots", service.getUpcomingAvailableSlots().size(),
                "bookings", all.size(),
                "completed", completed);
    }

    /** The most recent bookings, newest first, for the "recent bookings" line on the home page. */
    private Object activity() {
        List<Booking> all = service.getAllBookings();   // in the order they were made
        Collections.reverse(all);
        List<Object> out = new ArrayList<>();
        for (Booking b : all.subList(0, Math.min(all.size(), RECENT_BOOKINGS))) {
            Listing l = b.getSlot().getListing();
            out.add(obj("id", b.getId(), "learner", person(b.getLearner()), "tutor", person(b.getSlot().getTutor()),
                    "skill", l.getSkill(), "type", l.getType(), "status", b.getStatus().name(),
                    "bookedAt", b.getBookedAt().withNano(0).toString()));
        }
        return out;
    }

    /** What anyone can see about a student: what they teach, the times a learner can book, and how many sessions they completed. */
    private Object profileOf(Student st) {
        int taught = 0;
        int attended = 0;
        for (Booking b : service.getBookingsInvolving(st)) {
            if (b.getStatus() != BookingStatus.COMPLETED) continue;
            if (b.getLearner().equals(st)) attended++;
            else taught++;
        }
        // The same list the home page shows, so a profile never offers a slot that cannot be booked.
        List<TimeSlot> open = service.getUpcomingAvailableSlots().stream()
                .filter(t -> t.getTutor().equals(st)).toList();
        List<Object> listings = new ArrayList<>();
        for (Listing l : service.getListingsByTutor(st)) {
            listings.add(listingView(l, open.stream().filter(t -> t.getListing().getId().equals(l.getId())).toList()));
        }
        return obj("id", st.getId(), "name", st.getName(), "taught", taught, "attended", attended, "listings", listings);
    }

    private Object openSlots(String q, String type) {
        String k = q.trim().toLowerCase();
        return service.getUpcomingAvailableSlots().stream()
                .filter(t -> type.equals("ALL") || t.getListing().getType().equals(type))
                .filter(t -> k.isEmpty()
                        || t.getListing().getSkill().toLowerCase().contains(k)
                        || t.getTutor().getName().toLowerCase().contains(k))
                .map(this::slotView)
                .toList();
    }

    private Object bookingsOf(Student st) {
        List<Object> out = new ArrayList<>();
        for (Booking b : service.getBookingsInvolving(st)) out.add(bookingView(b, st));
        Collections.reverse(out);   // newest first
        return out;
    }

    private Object ledgerOf(Student st) {
        List<Object> history = new ArrayList<>();
        int earned = 0;
        for (CreditTransaction tx : ledger.getHistory(st)) {
            if (tx.getType() == CreditTransaction.Type.RELEASE) earned += tx.getAmount();
            // Say what the entry was for: the skill, and the other person in that booking.
            Booking b = tx.getBookingId() == null ? null : service.findBooking(tx.getBookingId());
            String skill = null;
            String other = null;
            if (b != null) {
                skill = b.getSlot().getListing().getSkill();
                other = (b.getLearner().equals(st) ? b.getSlot().getTutor() : b.getLearner()).getName();
            }
            history.add(obj("type", tx.getType().name(), "amount", tx.getAmount(),
                    "bookingId", tx.getBookingId(), "skill", skill, "with", other,
                    "time", tx.getTimestamp().withNano(0).toString()));
        }
        Collections.reverse(history);
        return obj("balance", ledger.getBalance(st), "held", service.getHeldCredits(st),
                "earned", earned, "history", history);
    }

    private Object listingsOf(Student st) {
        List<TimeSlot> slots = service.getSlotsForTutor(st);
        List<Object> out = new ArrayList<>();
        for (Listing l : service.getListingsByTutor(st)) {
            out.add(listingView(l, slots.stream().filter(t -> t.getListing().getId().equals(l.getId())).toList()));
        }
        return out;
    }

    // ---------- Changing ----------

    private Object addSlot(Student tutor, Map<String, String> p) throws BookingException {
        LocalDateTime start = LocalDateTime.parse(required(p, "start"));
        LocalDateTime now = LocalDateTime.now();
        if (!start.isAfter(now)) throw new IllegalArgumentException("Pick a time in the future");
        if (start.isAfter(now.plusDays(MAX_DAYS_AHEAD)))
            throw new IllegalArgumentException("Pick a time within the next " + MAX_DAYS_AHEAD + " days");
        TimeSlot slot = service.addSlot(tutor, required(p, "listingId"), start);
        save();
        return slotView(slot);
    }

    private Object createListing(Student tutor, Map<String, String> p) {
        String skill = text(p, "skill", "Skill", MAX_SKILL, true);
        String desc = text(p, "description", "Description", MAX_DESCRIPTION, false);
        int minutes = intInRange(p, "minutes", "Length", 15, 240);
        String type = p.getOrDefault("type", "");
        Listing listing;
        synchronized (service) {
            String id = service.nextListingId();
            // Pick the subclass here; the service treats every listing the same way.
            listing = switch (type) {
                case "PAID" -> new PaidListing(id, tutor, skill, desc, minutes, fee(p));
                case "SWAP" -> new CreditSwapListing(id, tutor, skill, desc, minutes,
                        intInRange(p, "credits", "Credits per session", 1, 10));
                case "FREE" -> new FreeListing(id, tutor, skill, desc, minutes);
                default -> throw new IllegalArgumentException("Choose a listing type");
            };
            service.addListing(listing);
        }
        save();
        return listingView(listing, List.of());
    }

    private Object book(Student learner, Map<String, String> p) throws BookingException {
        Booking b = service.book(learner, required(p, "slotId"));
        save();
        return bookingView(b, learner);
    }

    // ---------- JSON views ----------

    /** A student as they see themselves. The balance is private, so this is only ever sent to that student. */
    private Map<String, Object> studentView(Student s) {
        return obj("id", s.getId(), "name", s.getName(), "credits", ledger.getBalance(s));
    }

    /** A student as everyone else sees them. */
    private static Map<String, Object> person(Student s) {
        return obj("id", s.getId(), "name", s.getName());
    }

    private Map<String, Object> slotView(TimeSlot t) {
        Listing l = t.getListing();
        return obj("id", t.getId(), "listingId", l.getId(), "skill", l.getSkill(), "description", l.getDescription(),
                "type", l.getType(), "price", l.getPriceDescription(), "creditCost", l.getCreditCost(),
                "minutes", l.getDurationMinutes(), "tutor", person(t.getTutor()),
                "start", t.getStart().toString(), "end", t.getEnd().toString(), "booked", t.isBooked(),
                "busy", !t.isBooked() && service.isTutorBusy(t));
    }

    private Map<String, Object> listingView(Listing l, List<TimeSlot> slots) {
        return obj("id", l.getId(), "skill", l.getSkill(), "description", l.getDescription(), "type", l.getType(),
                "price", l.getPriceDescription(), "minutes", l.getDurationMinutes(),
                "slots", slots.stream().map(this::slotView).toList());
    }

    private Map<String, Object> bookingView(Booking b, Student viewer) {
        return obj("id", b.getId(), "status", b.getStatus().name(),
                "role", b.getLearner().equals(viewer) ? "LEARNING" : "TEACHING",
                "learner", person(b.getLearner()), "tutor", person(b.getSlot().getTutor()),
                "bookedAt", b.getBookedAt().withNano(0).toString(),
                "slot", slotView(b.getSlot()));
    }

    private static Map<String, Object> error(String type, String message) {
        return obj("type", type, "message", message == null ? "Something went wrong" : message);
    }

    // ---------- Helpers ----------

    private static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return v.trim();
    }

    /** A trimmed text field no longer than max characters. An optional field that is missing comes back as "". */
    private static String text(Map<String, String> p, String key, String label, int max, boolean isRequired) {
        String v = p.getOrDefault(key, "").trim();
        if (v.isEmpty() && isRequired) throw new IllegalArgumentException(label + " is required");
        if (v.length() > max) throw new IllegalArgumentException(label + " must be " + max + " characters or fewer");
        if (v.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + " cannot contain line breaks or other control characters");
        return v;
    }

    private static int intInRange(Map<String, String> p, String key, String label, int min, int max) {
        String range = label + " must be a whole number from " + min + " to " + max;
        int n;
        try {
            n = Integer.parseInt(required(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(range);
        }
        if (n < min || n > max) throw new IllegalArgumentException(range);
        return n;
    }

    private static double fee(Map<String, String> p) {
        String range = "Fee must be an amount from 1 to " + (long) MAX_FEE;
        double d;
        try {
            d = Double.parseDouble(required(p, "fee"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(range);
        }
        // Written as "not in range" so that NaN, which fails every comparison, is rejected too.
        if (!(d >= 1 && d <= MAX_FEE)) throw new IllegalArgumentException(range);
        return d;
    }

    /** Query string plus, for POST, a form-encoded body. */
    private static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> p = new HashMap<>();
        parseInto(p, ex.getRequestURI().getRawQuery());
        if (ex.getRequestMethod().equals("POST")) {
            parseInto(p, new String(ex.getRequestBody().readNBytes(64_000), StandardCharsets.UTF_8));
        }
        return p;
    }

    private static void parseInto(Map<String, String> p, String raw) {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                p.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {   // a stray % that is not followed by two hex digits
                throw new IllegalArgumentException("The request is not encoded correctly");
            }
        }
    }

    private void save() {
        try {
            store.save(service);
        } catch (PersistenceException e) {
            System.err.println("WARNING: changes were not saved: " + e.getMessage());
        }
    }

    private void saveWithReports() {
        try {
            store.save(service);
            store.exportSessionHistory(service);
            store.exportBalances(service);
            System.out.println("Saved. Reports written to data/.");
        } catch (PersistenceException e) {
            System.err.println("WARNING: " + e.getMessage());
        }
    }

    // ---------- Static files ----------

    private void handleStatic(HttpExchange ex) throws IOException {
        boolean head = ex.getRequestMethod().equals("HEAD");
        if (!head && !ex.getRequestMethod().equals("GET")) {
            send(ex, 405, new byte[0]);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file;
        try {
            file = webRoot.resolve(path.substring(1)).normalize();
        } catch (InvalidPathException e) {   // e.g. a %00 in the address
            file = null;
        }
        if (file == null || !file.startsWith(webRoot) || !Files.isRegularFile(file)) {   // also blocks ../ tricks
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");   // always pick up edits to the front end
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        send(ex, 200, head ? new byte[0] : Files.readAllBytes(file));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    /** A request the API refuses, with the HTTP status to answer with. */
    private static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String type;

        ApiException(int status, String type, String message) {
            super(message);
            this.status = status;
            this.type = type;
        }
    }
}
