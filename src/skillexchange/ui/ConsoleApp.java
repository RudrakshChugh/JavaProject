package skillexchange.ui;

import skillexchange.exception.BookingException;
import skillexchange.exception.PersistenceException;
import skillexchange.model.*;
import skillexchange.persistence.DataStore;
import skillexchange.service.BookingService;
import skillexchange.service.CreditLedger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Scanner;

/**
 * Text menu for the whole system. It holds no business rules: every action goes through
 * BookingService, and the service's exceptions are turned into messages for the user.
 * Data is saved automatically after every change.
 */
public class ConsoleApp {
    private static final int STARTING_CREDITS = 5;   // given to every newly registered student

    private final BookingService service;
    private final CreditLedger ledger;
    private final DataStore store;
    private final Input in;
    private Student current;   // logged-in student, or null

    public ConsoleApp(BookingService service, DataStore store, Scanner scanner) {
        this.service = service;
        this.ledger = service.getLedger();
        this.store = store;
        this.in = new Input(scanner);
    }

    public void run() {
        System.out.println("==============================================");
        System.out.println("  Peer Tutoring & Skill Exchange");
        System.out.println("==============================================");
        try {
            boolean running = true;
            while (running) {
                running = (current == null) ? guestMenu() : studentMenu();
            }
        } catch (Input.EndOfInputException e) {
            System.out.println("\n(input closed)");
        }
        exportReports();
        System.out.println("Goodbye!");
    }

    // ---------- Menus ----------

    /** Returns false to quit. */
    private boolean guestMenu() {
        System.out.println("\n--- Main menu ---");
        System.out.println("1. Log in");
        System.out.println("2. Register as a new student");
        System.out.println("3. Browse open slots");
        System.out.println("4. Search listings by skill");
        System.out.println("0. Exit");
        switch (in.intInRange("> ", 0, 4)) {
            case 1 -> logIn();
            case 2 -> register();
            case 3 -> browseOpenSlots();
            case 4 -> search();
            case 0 -> { return false; }
        }
        return true;
    }

    /** Returns false to quit. */
    private boolean studentMenu() {
        System.out.printf("%n--- %s | %d credits ---%n", current, ledger.getBalance(current));
        System.out.println(" 1. Browse open slots");
        System.out.println(" 2. Search listings by skill");
        System.out.println(" 3. Book a slot");
        System.out.println(" 4. My bookings");
        System.out.println(" 5. Cancel a booking");
        System.out.println(" 6. Confirm a session took place (pays the tutor)");
        System.out.println(" 7. Create a listing (teach a skill)");
        System.out.println(" 8. Add a time slot to my listing");
        System.out.println(" 9. My listings and slots");
        System.out.println("10. Credit balance and history");
        System.out.println(" 0. Log out");
        switch (in.intInRange("> ", 0, 10)) {
            case 1 -> browseOpenSlots();
            case 2 -> search();
            case 3 -> book();
            case 4 -> myBookings();
            case 5 -> cancel();
            case 6 -> confirmSession();
            case 7 -> createListing();
            case 8 -> addSlot();
            case 9 -> myListings();
            case 10 -> creditHistory();
            case 0 -> { System.out.println("Logged out."); current = null; }
        }
        return true;
    }

    // ---------- Guest actions ----------

    private void logIn() {
        List<Student> students = service.getAllStudents();
        if (students.isEmpty()) { System.out.println("No students yet. Register first."); return; }
        students.forEach(s -> System.out.println("  " + s));
        String id = in.required("Student id: ").toUpperCase();
        Student s = service.findStudent(id);
        if (s == null) { System.out.println("No student with id " + id); return; }
        current = s;
        System.out.println("Welcome, " + s.getName() + "!");
    }

    private void register() {
        String name = in.required("Name: ");
        String email = in.line("Email (optional): ");
        Student s = new Student(service.nextStudentId(), name, email.isEmpty() ? null : email);
        service.registerStudent(s, STARTING_CREDITS);
        save();
        current = s;
        System.out.println("Registered as " + s + " with " + STARTING_CREDITS
                + " starting credits. You are now logged in.");
    }

    private void browseOpenSlots() {
        List<TimeSlot> open = service.getAvailableSlots();
        if (open.isEmpty()) { System.out.println("No open slots right now."); return; }
        System.out.println("Open slots:");
        open.forEach(s -> System.out.println("  " + s));
    }

    private void search() {
        String keyword = in.required("Skill keyword: ");
        List<Listing> found = service.searchBySkill(keyword);
        if (found.isEmpty()) { System.out.println("No listings match \"" + keyword + "\"."); return; }
        for (Listing l : found) {
            System.out.println("  " + l);
            System.out.println("      " + l.getDescription());
        }
    }

    // ---------- Learner actions ----------

    private void book() {
        browseOpenSlots();
        String slotId = in.line("Slot id to book (blank to go back): ").toUpperCase();
        if (slotId.isEmpty()) return;
        TimeSlot slot = findOpenSlot(slotId);
        if (slot == null) { System.out.println("No open slot with id " + slotId); return; }

        Listing l = slot.getListing();
        if (!in.yesNo("Book " + l.getSkill() + " with " + slot.getTutor().getName()
                + " for " + l.getPriceDescription() + "?")) return;
        try {
            Booking b = service.book(current, slotId);
            save();
            System.out.println("Booked! " + b);
            if (l.getCreditCost() > 0) {
                System.out.println("Held until you confirm the session took place: " + l.getPriceDescription());
            }
        } catch (BookingException e) {
            System.out.println("Could not book: " + e.getMessage());
        }
    }

    private void myBookings() {
        List<Booking> list = service.getBookingsInvolving(current);
        if (list.isEmpty()) { System.out.println("You have no bookings yet."); return; }
        for (Booking b : list) {
            String role = b.getLearner().equals(current) ? "learning" : "teaching";
            System.out.printf("  %-9s %s, %s%n", role, b, b.getSlot().getStart().format(Input.DATE_TIME));
        }
    }

    private void cancel() {
        String id = pickConfirmedBooking(false);
        if (id == null) return;
        try {
            service.cancel(current, id);
            save();
            System.out.println("Booking " + id + " cancelled. The slot is open again, and any held credits went back to the learner.");
        } catch (BookingException e) {
            System.out.println("Could not cancel: " + e.getMessage());
        }
    }

    private void confirmSession() {
        String id = pickConfirmedBooking(true);
        if (id == null) return;
        try {
            service.completeSession(current, id);
            save();
            System.out.println("Session " + id + " marked as completed. Thanks!");
        } catch (BookingException e) {
            System.out.println("Could not confirm: " + e.getMessage());
        }
    }

    // ---------- Tutor actions ----------

    private void createListing() {
        System.out.println("Listing type: 1. Paid   2. Credit swap   3. Free");
        int type = in.intInRange("> ", 1, 3);
        String skill = in.required("Skill / subject: ");
        String desc = in.line("Short description: ");
        int minutes = in.intInRange("Session length in minutes (15-240): ", 15, 240);
        String id = service.nextListingId();

        // Pick the subclass here; everything after this point treats it as a plain Listing.
        Listing listing = switch (type) {
            case 1 -> new PaidListing(id, current, skill, desc, minutes, in.positiveDouble("Fee (Rs.): "));
            case 2 -> new CreditSwapListing(id, current, skill, desc, minutes,
                    in.intInRange("Credits per session (1-10): ", 1, 10));
            default -> new FreeListing(id, current, skill, desc, minutes);
        };
        service.addListing(listing);
        save();
        System.out.println("Created " + listing);
        System.out.println("Next, add a time slot (option 8) so students can book it.");
    }

    private void addSlot() {
        List<Listing> mine = service.getListingsByTutor(current);
        if (mine.isEmpty()) { System.out.println("You have no listings yet. Create one first (option 7)."); return; }
        mine.forEach(l -> System.out.println("  " + l));
        String listingId = in.required("Listing id: ").toUpperCase();
        if (mine.stream().noneMatch(l -> l.getId().equals(listingId))) {
            System.out.println("You have no listing with id " + listingId);
            return;
        }
        LocalDateTime start = in.futureDateTime("Start (yyyy-MM-dd HH:mm): ");
        try {
            TimeSlot slot = service.addSlot(listingId, start);
            save();
            System.out.println("Added " + slot);
        } catch (BookingException e) {
            System.out.println("Could not add slot: " + e.getMessage());
        }
    }

    private void myListings() {
        List<Listing> mine = service.getListingsByTutor(current);
        if (mine.isEmpty()) { System.out.println("You don't teach anything yet."); return; }
        System.out.println("Listings:");
        mine.forEach(l -> System.out.println("  " + l));
        List<TimeSlot> slots = service.getSlotsForTutor(current);
        System.out.println("Slots:");
        if (slots.isEmpty()) System.out.println("  (none)");
        slots.forEach(s -> System.out.println("  " + s));
    }

    private void creditHistory() {
        System.out.println("Balance: " + ledger.getBalance(current) + " credits");
        for (CreditTransaction tx : ledger.getHistory(current)) {
            System.out.println("  " + tx.getTimestamp().format(Input.DATE_TIME) + "  " + tx);
        }
    }

    // ---------- Helpers ----------

    private TimeSlot findOpenSlot(String slotId) {
        for (TimeSlot s : service.getAvailableSlots()) {
            if (s.getId().equals(slotId)) return s;
        }
        return null;
    }

    /**
     * Lists the current user's confirmed bookings and asks them to choose one.
     * learnerOnly = true shows only sessions they are attending (for confirming).
     * Returns null if there is nothing to choose or the user goes back.
     */
    private String pickConfirmedBooking(boolean learnerOnly) {
        List<Booking> options = service.getBookingsInvolving(current).stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .filter(b -> !learnerOnly || b.getLearner().equals(current))
                .toList();
        if (options.isEmpty()) { System.out.println("No confirmed bookings to choose from."); return null; }
        options.forEach(b -> System.out.println("  " + b));
        String id = in.line("Booking id (blank to go back): ").toUpperCase();
        if (id.isEmpty()) return null;
        if (options.stream().noneMatch(b -> b.getId().equals(id))) {
            System.out.println("Not one of the bookings listed: " + id);
            return null;
        }
        return id;
    }

    private void save() {
        try {
            store.save(service);
        } catch (PersistenceException e) {
            System.out.println("WARNING: changes were not saved: " + e.getMessage());
        }
    }

    private void exportReports() {
        try {
            store.save(service);
            System.out.println("Saved. Reports written to " + store.exportSessionHistory(service)
                    + " and " + store.exportBalances(service));
        } catch (PersistenceException e) {
            System.out.println("WARNING: " + e.getMessage());
        }
    }
}
