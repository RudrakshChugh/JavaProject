package skillexchange.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.AuthException;
import skillexchange.exception.ModuleFormatException;
import skillexchange.exception.NotFoundException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.exception.ValidationException;
import skillexchange.io.ModuleFileParser;
import skillexchange.io.SessionCsv;
import skillexchange.model.AttemptResult;
import skillexchange.model.Booking;
import skillexchange.model.Conversation;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.ModuleDraft;
import skillexchange.model.Question;
import skillexchange.model.SessionMode;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.validation.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The web site: the same application as the JavaFX client, for a browser.
 *
 * It is server-side Java. Every request arrives here, a Java method does the work through the
 * same {@link SkillExchangeApi} the desktop client uses, and another Java method ({@link Pages})
 * writes the page that goes back. The browser runs no JavaScript: it follows links and submits forms.
 *
 * Each action follows the pattern Post, Redirect, Get: a form is posted, the server does the work
 * and answers with a redirect to a page, and the browser then gets that page. So reloading a page
 * never repeats an action.
 */
public final class WebSite {
    private static final Logger LOG = Logger.getLogger(WebSite.class.getName());

    private static final String COOKIE = "sx";
    private static final long IDLE_MILLIS = 30 * 60_000L;
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final int MAX_VISITS = 5000;

    private final Supplier<SkillExchangeApi> apiFactory;
    private final Map<String, Visit> visits = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Pages pages = new Pages();
    private final byte[] stylesheet;

    /** One request, already taken apart: who is asking, for which path, with which values. A nested record. */
    record Request(String method, String path, String here, Map<String, String> query, Map<String, String> form,
                   Visit visit, boolean dark) {

        /** A value from the posted form, or else from the address, or else "". */
        String get(String name) {
            String value = form.get(name);
            if (value == null) value = query.get(name);
            return value == null ? "" : value;
        }

        long id(String name) throws NotFoundException {
            try {
                return Long.parseLong(get(name).strip());
            } catch (NumberFormatException e) {
                throw new NotFoundException(name, 0);
            }
        }

        /** The signed-in student. Throws if nobody is signed in, which sends the visitor to the sign-in page. */
        Student me() throws AuthException {
            Student me = visit.me;
            if (me == null) throw new AuthException("Sign in first");
            return me;
        }
    }

    /** What a handler answers with. Sealed: these four records are the only kinds of answer. */
    sealed interface Response permits Page, Redirect, Download, Document { }

    /** A page of the site; {@link Pages#layout} puts the frame around the body. */
    record Page(String tab, String title, String body, int credits) implements Response { }

    record Redirect(String to) implements Response { }

    record Download(String fileName, String contentType, byte[] body) implements Response { }

    /** A complete HTML document that needs no frame: the message list inside the chat. */
    record Document(String html) implements Response { }

    /** @param apiFactory makes the API object for a new visitor, the same kind a desktop client talks to */
    public WebSite(Supplier<SkillExchangeApi> apiFactory) throws IOException {
        this.apiFactory = apiFactory;
        try (InputStream in = WebSite.class.getResourceAsStream("/web/styles.css")) {
            if (in == null) throw new IOException("Missing resource /web/styles.css");
            this.stylesheet = in.readAllBytes();
        }
    }

    /** Makes this site answer every address of the HTTP server that nothing more specific handles. */
    public void attach(HttpServer http) {
        http.createContext("/", this::handle);
    }

    /** Ends visits that have been idle for half an hour. Called by the server's housekeeping thread. */
    public int expireIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_MILLIS;
        int ended = 0;
        for (Visit visit : visits.values()) {
            if (visit.lastSeen < cutoff && visits.remove(visit.id, visit)) {
                signOutQuietly(visit);
                ended++;
            }
        }
        return ended;
    }

    private static void signOutQuietly(Visit visit) {
        try {
            visit.api.logout();
        } catch (SkillExchangeException e) {
            LOG.log(Level.FINE, "Ending a visit failed", e);
        }
    }

    // ---------- One request ----------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/styles.css")) {
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                send(ex, 200, "text/css; charset=utf-8", stylesheet);
                return;
            }
            boolean post = method.equals("POST");
            if (!post && !method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Visit visit = visitFor(ex);
            String rawQuery = ex.getRequestURI().getRawQuery();
            Map<String, String> form = post ? decode(readForm(ex)) : Map.of();
            // "here" is the page to come back to. A page shown in answer to a form has no address of
            // its own that can be fetched again, so for those it is the page the form came from.
            String here = post ? localPath(form.get("back")) : path + (rawQuery == null ? "" : "?" + rawQuery);
            Request rq = new Request(method, path, here, decode(rawQuery), form, visit, "dark".equals(cookies(ex).get("theme")));

            Response response;
            try {
                // A form must carry the secret of the visit it was made for. A page on another site
                // cannot know it, so it cannot make a signed-in visitor's browser act for it.
                if (post && !visit.csrf.equals(rq.form().get("csrf")))
                    throw new SkillExchangeException("That page was out of date. Please try again.");
                response = route(rq, ex);
            } catch (SkillExchangeException e) {
                if (e instanceof AuthException && !visit.signedIn()) {
                    // A page that needs an account: sign in first, then come back to where you were going.
                    response = new Redirect("/signin?next=" + Html.url(post ? back(rq) : rq.here()));
                } else if (!post) {
                    response = pages.message(rq, "", "That did not work", e.getMessage());
                } else {
                    visit.flash(e.getMessage(), true);      // shown once on the page the visitor is sent back to
                    response = new Redirect(back(rq));
                }
            }
            write(ex, rq, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Request " + ex.getRequestURI().getPath() + " failed", e);
            if (ex.getResponseCode() == -1)
                send(ex, 500, "text/plain; charset=utf-8", "Something went wrong on the server. It has been logged.".getBytes(StandardCharsets.UTF_8));
        } finally {
            ex.close();
        }
    }

    /** Sends the right handler for the method and the path. The whole site is this one switch. */
    private Response route(Request rq, HttpExchange ex) throws Exception {
        SkillExchangeApi api = rq.visit().api;
        return switch (rq.method().replace("HEAD", "GET") + " " + rq.path()) {
            case "GET /" -> pages.browse(rq);
            case "GET /surprise" -> surprise(rq);
            case "GET /book" -> {
                rq.me();
                yield pages.book(rq, openSlot(rq));
            }
            case "POST /book" -> {
                Booking booking = api.book(rq.id("slot"));
                rq.visit().flash("Booked " + booking.getSlot().getListing().getSkill() + ". " + booking.getSlot().getMode().getPlaceLabel()
                        + ": " + booking.getSlot().getPlace(), false);
                yield new Redirect("/bookings");
            }
            case "GET /tutor" -> pages.tutor(rq, api.tutorProfile(rq.id("id")));

            case "GET /signin" -> rq.visit().signedIn() ? new Redirect("/") : pages.signIn(rq, Map.of());
            case "POST /signin" -> signIn(rq);
            case "POST /register" -> register(rq);
            case "GET /account" -> pages.account(rq);
            case "POST /signout" -> {
                api.logout();
                rq.visit().me = null;
                rq.visit().flash("Signed out", false);
                yield new Redirect("/");
            }
            case "POST /theme" -> {
                // The theme is remembered in a cookie of its own, so it survives signing out.
                ex.getResponseHeaders().add("Set-Cookie", "theme=" + (rq.dark() ? "light" : "dark") + "; Path=/; Max-Age=31536000; SameSite=Lax");
                yield new Redirect(back(rq));
            }

            case "GET /bookings" -> pages.bookings(rq);
            case "GET /bookings/confirm" -> pages.confirmBooking(rq, myBooking(rq), rq.get("do").equals("complete"));
            case "POST /bookings/cancel" -> {
                api.cancel(rq.id("id"));
                yield done(rq, "Booking cancelled", "/bookings");
            }
            case "POST /bookings/complete" -> {
                api.complete(rq.id("id"));
                yield done(rq, "Session confirmed. Thank you!", "/bookings");
            }
            case "GET /bookings/export" -> export(rq);

            case "GET /teach" -> pages.teach(rq);
            case "POST /teach/listing" -> createListing(rq);
            case "GET /teach/delete" -> pages.deleteListing(rq, myListing(rq));
            case "POST /teach/delete" -> {
                api.deleteListing(rq.id("listing"));
                yield done(rq, "Listing deleted", "/teach");
            }
            case "POST /teach/slot" -> addSlot(rq);
            case "POST /teach/slot/remove" -> {
                api.removeSlot(rq.id("slot"));
                yield done(rq, "Slot removed", "/teach");
            }
            case "GET /teach/module" -> pages.writeModule(rq, myListing(rq), null, null);
            case "POST /teach/module" -> saveModule(rq);
            case "POST /teach/module/delete" -> {
                api.deleteModule(rq.id("module"));
                yield done(rq, "Module deleted", "/teach");
            }
            case "GET /teach/exercises" -> pages.exercises(rq, myModule(rq));
            case "GET /teach/scores" -> pages.scores(rq, myModule(rq), api.moduleScores(rq.id("module")));

            case "GET /messages" -> pages.messages(rq);
            case "GET /messages/thread" -> new Document(pages.thread(rq, api.messages(rq.id("c"))));
            case "POST /messages/open" -> {
                // Opened from a session card (by listing) or from a booking (by booking).
                Conversation c = rq.get("booking").isEmpty() ? api.openConversation(rq.id("listing")) : api.openBookingConversation(rq.id("booking"));
                yield new Redirect("/messages?c=" + c.id());
            }
            case "POST /messages/send" -> {
                api.sendMessage(rq.id("c"), rq.get("text"));
                yield new Redirect("/messages?c=" + rq.id("c"));
            }

            case "GET /learn" -> pages.learn(rq);
            case "GET /learn/module" -> quiz(rq);
            case "POST /learn/submit" -> submitQuiz(rq);

            case "GET /credits" -> pages.credits(rq);
            case "GET /how" -> pages.how(rq, null);
            case "POST /how/race" -> pages.how(rq, api.raceDemo());

            default -> pages.message(rq, "", "Page not found", "There is nothing at " + rq.path() + ".");
        };
    }

    private Redirect done(Request rq, String message, String to) {
        rq.visit().flash(message, false);
        return new Redirect(to);
    }

    // ---------- Actions ----------

    private Response signIn(Request rq) throws SkillExchangeException {
        try {
            rq.visit().me = rq.visit().api.login(rq.get("email"), rq.get("password").toCharArray());
        } catch (AuthException wrong) {
            // Shown again with the email kept and the message under the password field.
            return pages.signIn(rq, Map.of("login", wrong.getMessage()));
        }
        rq.visit().flash("Signed in as " + rq.visit().me.getName(), false);
        return new Redirect(localPath(rq.get("next")));
    }

    private Response register(Request rq) throws SkillExchangeException {
        // Every field is checked, and all the problems are collected, so the visitor sees them all at once.
        Map<String, String> errors = new LinkedHashMap<>();
        Validator.errorOf(() -> Validator.name(rq.get("name"))).ifPresent(m -> errors.put("name", m));
        Validator.errorOf(() -> Validator.email(rq.get("newEmail"))).ifPresent(m -> errors.put("email", m));
        Validator.errorOf(() -> Validator.password(rq.get("newPassword").toCharArray())).ifPresent(m -> errors.put("password", m));
        if (!rq.get("newPassword").equals(rq.get("confirm"))) errors.put("confirm", "The two passwords are different");
        if (errors.isEmpty()) {
            try {
                rq.visit().me = rq.visit().api.register(rq.get("name"), rq.get("newEmail"), rq.get("newPassword").toCharArray());
            } catch (ValidationException invalid) {         // the email is already taken, for example
                errors.put(invalid.getField(), invalid.getMessage());
            }
        }
        if (!errors.isEmpty()) return pages.signIn(rq, errors);
        rq.visit().flash("Welcome, " + rq.visit().me.getName() + ". You start with 5 credits.", false);
        return new Redirect(localPath(rq.get("next")));
    }

    /** Sends the visitor to the booking page of a random session they could actually book. */
    private Response surprise(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        int credits = v.signedIn() ? v.api.credits().balance() : Integer.MAX_VALUE;
        List<TimeSlot> options = v.api.openSlots("", null).stream()
                .filter(s -> !v.isMe(s.getTutor()) && s.getListing().getCreditCost() <= credits)
                .toList();
        if (options.isEmpty()) {
            v.flash("There is nothing you can book right now.", true);
            return new Redirect("/");
        }
        return new Redirect("/book?slot=" + options.get(new Random().nextInt(options.size())).getId());
    }

    private Response createListing(Request rq) throws SkillExchangeException {
        ListingType type;
        try {
            type = ListingType.valueOf(rq.get("type"));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("type", "Choose a listing type");
        }
        int minutes = Validator.wholeNumber("duration", "Length", rq.get("minutes"));
        // Only the price that belongs to the chosen type is read; the other field may hold anything.
        double fee = type == ListingType.PAID ? Validator.amount("fee", "Fee", rq.get("fee")) : 0;
        int credits = type == ListingType.SWAP ? Validator.wholeNumber("credits", "Credits", rq.get("credits")) : 0;
        Listing created = rq.visit().api.createListing(type, rq.get("skill"), rq.get("description"), minutes, fee, credits);
        return done(rq, "Listing created: " + created.getSkill() + ". Now add a time slot.", "/teach");
    }

    private Response addSlot(Request rq) throws SkillExchangeException {
        LocalDateTime start;
        SessionMode mode;
        try {
            start = LocalDateTime.parse(rq.get("start"));       // the browser sends 2026-11-26T10:00
            mode = SessionMode.valueOf(rq.get("mode"));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ValidationException("start", "Choose a date, a time and where the session takes place");
        }
        TimeSlot slot = rq.visit().api.addSlot(rq.id("listing"), start, mode, rq.get("place"));
        return done(rq, "Slot added: " + Html.shortDay(slot.getStart()) + ", " + Html.timeRange(slot), "/teach");
    }

    private Response saveModule(Request rq) throws SkillExchangeException, IOException {
        Listing listing = myListing(rq);
        String text = rq.get("text");
        int saved = 0;
        // Multi-catch: a mistake in the text, or a module the rules refuse, shows the page again with the text kept.
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            for (ModuleDraft draft : ModuleFileParser.parse(reader)) {
                rq.visit().api.createModule(listing.getId(), draft);
                saved++;
            }
        } catch (ModuleFormatException | ValidationException invalid) {
            return pages.writeModule(rq, listing, text, invalid.getMessage());
        }
        return done(rq, Html.plural(saved, "module") + " added to " + listing.getSkill(), "/teach");
    }

    private Response quiz(Request rq) throws SkillExchangeException {
        rq.me();
        long id = rq.id("id");
        String title = rq.visit().api.modules("").stream().filter(m -> m.id() == id).map(m -> m.title()).findFirst()
                .orElseThrow(() -> new NotFoundException("module", id));
        return pages.quiz(rq, id, title, rq.visit().api.questions(id));
    }

    private Response submitQuiz(Request rq) throws SkillExchangeException {
        long id = rq.id("module");
        // The form sends one field per question, named "a" + the question's id.
        Map<Long, String> answers = new HashMap<>();
        for (Question q : rq.visit().api.questions(id)) {
            answers.put(q.id(), rq.get("a" + q.id()));
        }
        AttemptResult result = rq.visit().api.submitAnswers(id, answers);
        return pages.quizResult(rq, id, result);
    }

    /** The session history as a CSV file the browser downloads. */
    private Response export(Request rq) throws SkillExchangeException, IOException {
        Student me = rq.me();
        Path temp = Files.createTempFile("sessions", ".csv");
        try {
            SessionCsv.write(temp, me, rq.visit().api.myBookings());
            return new Download("my-sessions.csv", "text/csv; charset=utf-8", Files.readAllBytes(temp));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---------- Finding the thing a request is about ----------

    private static TimeSlot openSlot(Request rq) throws SkillExchangeException {
        long id = rq.id("slot");
        return rq.visit().api.openSlots("", null).stream().filter(s -> s.getId() == id).findFirst()
                .orElseThrow(() -> new SkillExchangeException("That session is no longer available."));
    }

    private static Booking myBooking(Request rq) throws SkillExchangeException {
        long id = rq.id("id");
        return rq.visit().api.myBookings().stream().filter(b -> b.getId() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("booking", id));
    }

    private static Listing myListing(Request rq) throws SkillExchangeException {
        long id = rq.id("listing");
        return rq.visit().api.myListings().stream().filter(l -> l.getId() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("listing", id));
    }

    private static LearningModule myModule(Request rq) throws SkillExchangeException {
        long id = rq.id("module");
        return rq.visit().api.myModules().stream().filter(m -> m.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("module", id));
    }

    // ---------- Visits and cookies ----------

    /** The visit this browser belongs to, found by its cookie. A browser without one gets a new visit. */
    private Visit visitFor(HttpExchange ex) {
        String id = cookies(ex).get(COOKIE);
        Visit visit = id == null ? null : visits.get(id);
        if (visit == null) {
            if (visits.size() >= MAX_VISITS) expireIdle();
            visit = new Visit(token(), token(), apiFactory.get());
            visits.put(visit.id, visit);
            // HttpOnly: page scripts could not read it. SameSite: other sites cannot send it along.
            boolean https = "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + visit.id + "; Path=/; HttpOnly; SameSite=Lax" + (https ? "; Secure" : ""));
        }
        visit.lastSeen = System.currentTimeMillis();
        return visit;
    }

    private String token() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<String, String> cookies(HttpExchange ex) {
        Map<String, String> cookies = new HashMap<>();
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return cookies;
        for (String header : headers) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) cookies.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        return cookies;
    }

    // ---------- Reading and writing ----------

    private static String readForm(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_FORM_BYTES + 1);
            if (body.length > MAX_FORM_BYTES) throw new IOException("The form is too large");
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Turns "a=1&b=two+words" into a map. Used for the address's query and for a posted form. */
    private static Map<String, String> decode(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return values;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                values.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                LOG.fine(() -> "Ignoring a malformed form value: " + pair);
            }
        }
        return values;
    }

    /** Where to send the visitor after a form: the page the form named, if that is a page of this site. */
    private static String back(Request rq) {
        return localPath(rq.form().get("back"));
    }

    /**
     * Accepts only a path on this site, such as /teach. Anything else, for example the address
     * of another site, becomes "/", so a link cannot use our redirect to send someone elsewhere.
     */
    static String localPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.chars().anyMatch(c -> c < 0x20)) return "/";
        return path;
    }

    private void write(HttpExchange ex, Request rq, Response response) throws IOException {
        // Pattern matching for instanceof: each kind of answer is written its own way.
        if (response instanceof Redirect redirect) {
            ex.getResponseHeaders().set("Location", redirect.to());
            ex.sendResponseHeaders(303, -1);        // 303 See Other: "now GET this page"
        } else if (response instanceof Download file) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"");
            send(ex, 200, file.contentType(), file.body());
        } else {
            String html = response instanceof Page page ? pages.layout(rq, page) : ((Document) response).html();
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            // The browser is told to run no scripts at all and to load styles and fonts only from here and Google Fonts.
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'none'; "
                    + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                    + "frame-ancestors 'self'; form-action 'self'; base-uri 'none'");
            send(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
