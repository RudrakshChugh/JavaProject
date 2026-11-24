package skillexchange;

import skillexchange.exception.PersistenceException;
import skillexchange.persistence.DataStore;
import skillexchange.service.BookingService;
import skillexchange.ui.ConsoleApp;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * Entry point. Loads saved data (or demo data on the first run), then starts the console menu.
 *   java -cp out skillexchange.Main           normal start
 *   java -cp out skillexchange.Main --reset   delete saved data and start fresh with demo data
 */
public class Main {
    public static void main(String[] args) throws Exception {
        DataStore store = new DataStore(Path.of("data"));
        if (args.length > 0 && args[0].equals("--reset")) {
            store.reset();
            System.out.println("Saved data cleared.");
        }

        BookingService service;
        try {
            service = loadOrSeed(store);
        } catch (PersistenceException e) {
            System.out.println("ERROR: " + e.getMessage());
            System.out.println("Run with --reset to start fresh (this deletes saved data).");
            return;
        }

        new ConsoleApp(service, store, new Scanner(System.in)).run();
    }

    /** Loads saved data, or seeds the demo data when there are no students yet. Shared with the web server. */
    public static BookingService loadOrSeed(DataStore store) throws Exception {
        BookingService service = store.loadOrCreate();
        if (service.getAllStudents().isEmpty()) {
            DemoData.seed(service);
            store.save(service);
            System.out.println("First run: demo data loaded. Log in as S1-S4, or register.");
        }
        return service;
    }
}
