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
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;

import static skillexchange.web.Json.obj;

/**
 * Web front end. Serves the static site in web/ and a small JSON API over BookingService,
 * using only the JDK's built-in HttpServer. Like ConsoleApp, it holds no business rules.
 * Requests run on a thread pool, so the synchronized service is what keeps concurrent bookings safe.
 *
 *   java -cp out skillexchange.web.WebServer            start on port 8080
 *   java -cp out skillexchange.web.WebServer 9090       another port
 *   java -cp out skillexchange.web.WebServer --reset    delete saved data and start with demo data
 */
public class WebServer {
    private static final int STARTING_CREDITS = 5;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png",
            "ico", "image/x-icon", "json", "application/json; charset=utf-8");

    private final BookingService service;
    private final CreditLedger ledger;
    private final DataStore store;
    private final Path webRoot;

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
            if (a.equals("--reset")) reset = true;
            else port = Integer.parseInt(a);
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
        new WebServer(service, store, webRoot).start(port);
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
            body = route(ex.getRequestMethod(), path, params(ex));
        } catch (BookingException e) {
            status = 409;
            body = error(e.getClass().getSimpleName(), e.getMessage());
        } catch (DateTimeParseException e) {
            status = 400;
            body = error("DateTimeParseException", "Enter a valid date and time");
        } catch (IllegalArgumentException e) {
            status = 400;
            body = error(e.getClass().getSimpleName(), e.getMessage());
        } catch (NotFoundException e) {
            status = 404;
            body = error("NotFound", e.getMessage());
        } catch (Exception e) {
            status = 500;
            body = error(e.getClass().getSimpleName(), "Something went wrong on the server");
            e.printStackTrace();
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, bytes);
    }

    private Object route(String method, String path, Map<String, String> p) throws Exception {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        String[] s = path.split("/");

        if (get && path.equals("stats")) return stats();
        if (get && path.equals("students")) return students();
        if (post && path.equals("students")) return register(p);
        if (get && s.length == 3 && s[0].equals("students")) {
            Student st = student(s[1]);
            switch (s[2]) {
                case "bookings": return bookingsOf(st);
                case "ledger": return ledgerOf(st);
                case "listings": return listingsOf(st);
                default: break;
            }
        }
        if (get && path.equals("slots")) return openSlots(p.getOrDefault("q", ""), p.getOrDefault("type", "ALL"));
        if (post && path.equals("slots")) return addSlot(p);
        if (post && path.equals("listings")) return createListing(p);
        if (post && path.equals("bookings")) return book(p);
        if (post && s.length == 3 && s[0].equals("bookings")) {
            Student actor = student(p.get("studentId"));
            if (s[2].equals("cancel")) {
                service.cancel(actor, s[1]);
                save();
                return obj("ok", true);
            }
            if (s[2].equals("complete")) {
                service.completeSession(actor, s[1]);
                save();
                return obj("ok", true);
            }
        }
        if (post && path.equals("demo/race")) return RaceDemo.run();
        throw new NotFoundException("No such endpoint: " + method + " /api/" + path);
    }

    private Object stats() {
        List<Booking> all = service.getAllBookings();
        long completed = all.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        return obj("students", service.getAllStudents().size(),
                "listings", service.getAllListings().size(),
                "openSlots", upcomingOpenSlots().size(),
                "bookings", all.size(),
                "completed", completed);
    }

    private Object students() {
        return service.getAllStudents().stream().map(this::studentView).toList();
    }

    private Object register(Map<String, String> p) {
        String name = required(p, "name");
        String email = p.getOrDefault("email", "").trim();
        Student s;
        // Hold the service's lock so no other request can take the same new id in between.
        synchronized (service) {
            s = new Student(service.nextStudentId(), name, email.isEmpty() ? null : email);
            service.registerStudent(s, STARTING_CREDITS);
        }
        save();
        return studentView(s);
    }

    private Object openSlots(String q, String type) {
        String k = q.trim().toLowerCase();
        return upcomingOpenSlots().stream()
                .filter(t -> type.equals("ALL") || t.getListing().getType().equals(type))
                .filter(t -> k.isEmpty()
                        || t.getListing().getSkill().toLowerCase().contains(k)
                        || t.getTutor().getName().toLowerCase().contains(k))
                .map(this::slotView)
                .toList();
    }

    private Object addSlot(Map<String, String> p) throws BookingException {
        Student tutor = student(p.get("studentId"));
        LocalDateTime start = LocalDateTime.parse(required(p, "start"));
        if (!start.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Pick a time in the future");
        TimeSlot slot = service.addSlot(tutor, required(p, "listingId"), start);
        save();
        return slotView(slot);
    }

    private Object createListing(Map<String, String> p) {
        Student tutor = student(p.get("studentId"));
        String skill = required(p, "skill");
        String desc = p.getOrDefault("description", "").trim();
        int minutes = intInRange(p, "minutes", 15, 240);
        String type = p.getOrDefault("type", "");
        Listing listing;
        synchronized (service) {
            String id = service.nextListingId();
            // Pick the subclass here; the service treats every listing the same way.
            listing = switch (type) {
                case "PAID" -> new PaidListing(id, tutor, skill, desc, minutes, positive(p, "fee"));
                case "SWAP" -> new CreditSwapListing(id, tutor, skill, desc, minutes, intInRange(p, "credits", 1, 10));
                case "FREE" -> new FreeListing(id, tutor, skill, desc, minutes);
                default -> throw new IllegalArgumentException("Choose a listing type");
            };
            service.addListing(listing);
        }
        save();
        return listingView(listing, List.of());
    }

    private Object book(Map<String, String> p) throws BookingException {
        Student learner = student(p.get("studentId"));
        Booking b = service.book(learner, required(p, "slotId"));
        save();
        return bookingView(b, learner);
    }

    private Object bookingsOf(Student st) {
        List<Object> out = new ArrayList<>();
        for (Booking b : service.getBookingsInvolving(st)) out.add(bookingView(b, st));
        Collections.reverse(out);   // newest first
        return out;
    }

    private Object ledgerOf(Student st) {
        List<Object> history = new ArrayList<>();
        for (CreditTransaction tx : ledger.getHistory(st)) {
            history.add(obj("type", tx.getType().name(), "amount", tx.getAmount(),
                    "bookingId", tx.getBookingId(), "time", tx.getTimestamp().withNano(0).toString()));
        }
        Collections.reverse(history);
        return obj("balance", ledger.getBalance(st), "history", history);
    }

    private Object listingsOf(Student st) {
        List<TimeSlot> slots = service.getSlotsForTutor(st);
        List<Object> out = new ArrayList<>();
        for (Listing l : service.getListingsByTutor(st)) {
            out.add(listingView(l, slots.stream().filter(t -> t.getListing().getId().equals(l.getId())).toList()));
        }
        return out;
    }

    // ---------- JSON views ----------

    private Map<String, Object> studentView(Student s) {
        return obj("id", s.getId(), "name", s.getName(), "email", s.getEmail(), "credits", ledger.getBalance(s));
    }

    private static Map<String, Object> person(Student s) {
        return obj("id", s.getId(), "name", s.getName());
    }

    private Map<String, Object> slotView(TimeSlot t) {
        Listing l = t.getListing();
        return obj("id", t.getId(), "listingId", l.getId(), "skill", l.getSkill(), "description", l.getDescription(),
                "type", l.getType(), "price", l.getPriceDescription(), "creditCost", l.getCreditCost(),
                "minutes", l.getDurationMinutes(), "tutor", person(t.getTutor()),
                "start", t.getStart().toString(), "end", t.getEnd().toString(), "booked", t.isBooked());
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
                "slot", slotView(b.getSlot()));
    }

    private static Map<String, Object> error(String type, String message) {
        return obj("type", type, "message", message == null ? "Something went wrong" : message);
    }

    // ---------- Helpers ----------

    private List<TimeSlot> upcomingOpenSlots() {
        LocalDateTime now = LocalDateTime.now();
        return service.getAvailableSlots().stream().filter(t -> t.getStart().isAfter(now)).toList();
    }

    private Student student(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Sign in first");
        Student s = service.findStudent(id);
        if (s == null) throw new IllegalArgumentException("Unknown student: " + id);
        return s;
    }

    private static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return v.trim();
    }

    private static int intInRange(Map<String, String> p, String key, int min, int max) {
        int n = Integer.parseInt(required(p, key));
        if (n < min || n > max) throw new IllegalArgumentException(key + " must be from " + min + " to " + max);
        return n;
    }

    private static double positive(Map<String, String> p, String key) {
        double d = Double.parseDouble(required(p, key));
        if (d <= 0) throw new IllegalArgumentException(key + " must be greater than 0");
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
            p.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
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
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, new byte[0]);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {   // blocks ../ tricks
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
        send(ex, 200, Files.readAllBytes(file));
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

    private static final class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFoundException(String message) { super(message); }
    }
}
