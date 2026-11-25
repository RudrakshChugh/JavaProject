package skillexchange.service;

import skillexchange.persistence.BookingDao;
import skillexchange.persistence.ChatDao;
import skillexchange.persistence.Database;
import skillexchange.persistence.LedgerDao;
import skillexchange.persistence.ListingDao;
import skillexchange.persistence.ModuleDao;
import skillexchange.persistence.SlotDao;
import skillexchange.persistence.StudentDao;

/** The service layer in one object: built once at start-up and shared by every client connection. */
public record Services(Database db, AppClock clock, AccountService accounts, BookingService bookings,
                       ChatService chat, LearningService learning) {

    /** Wires the Data Access Objects into the services that use them. */
    public static Services create(Database db) {
        AppClock clock = new AppClock();
        StudentDao students = new StudentDao();
        ListingDao listings = new ListingDao();
        SlotDao slots = new SlotDao();
        BookingDao bookings = new BookingDao();
        LedgerDao ledgerDao = new LedgerDao();
        CreditLedger ledger = new CreditLedger(students, ledgerDao);
        return new Services(db, clock,
                new AccountService(db, clock, students, ledger),
                new BookingService(db, clock, students, listings, slots, bookings, ledgerDao, ledger),
                new ChatService(db, clock, new ChatDao(), listings),
                new LearningService(db, clock, new ModuleDao(), listings));
    }
}
