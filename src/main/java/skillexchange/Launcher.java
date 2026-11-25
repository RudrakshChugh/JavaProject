package skillexchange;

import skillexchange.model.RaceReport;
import skillexchange.server.ServerMain;
import skillexchange.service.RaceDemo;
import skillexchange.ui.App;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One entry point for the three programs in this project:
 *
 * <pre>
 * Launcher            the desktop application (starts its own server if none is running)
 * Launcher server     only the server
 * Launcher demo       the concurrency demonstration, printed to the console
 * </pre>
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "app" : args[0];
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (mode) {
            case "server" -> ServerMain.main(rest);
            case "demo" -> {
                // Only the result matters here, so the services' own log lines are turned down.
                Logger.getLogger("skillexchange").setLevel(Level.WARNING);
                RaceReport report = RaceDemo.run();
                report.lines().forEach(System.out::println);
            }
            case "app" -> App.main(rest);
            default -> App.main(args);      // "Launcher --port=6000" is the app with options
        }
    }
}
