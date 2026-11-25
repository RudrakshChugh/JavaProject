package skillexchange.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import skillexchange.api.Event;
import skillexchange.logging.LogConfig;
import skillexchange.model.Student;
import skillexchange.net.RemoteApi;
import skillexchange.server.ServerMain;
import skillexchange.server.SkillExchangeServer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * The JavaFX desktop client.
 *
 * <pre>
 * App [--host=localhost] [--port=5050] [--data=data]      a server reached by socket
 * App --server=https://example.onrender.com               a server reached through the web
 * </pre>
 *
 * It connects to a Skill Exchange server. If none is running on this computer it starts one
 * inside its own process, so a single command is enough to try the application.
 */
public final class App extends Application {
    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1");

    static final String STYLESHEET = App.class.getResource("/ui/app.css").toExternalForm();

    // Remembers the chosen theme between runs. Preferences is the JDK's small per-user settings store.
    private final Preferences preferences = Preferences.userNodeForPackage(App.class);

    private String host;
    private int port;
    private String serverUrl;           // set with --server=https://...; null means connect by socket
    private Path dataDir;
    private Stage stage;
    private Scene scene;
    private MainView main;
    private boolean dark;
    // volatile: set on a background thread when reconnecting, read on the JavaFX thread.
    private volatile RemoteApi connection;
    private volatile SkillExchangeServer embeddedServer;
    private String startupError;
    // Where server events go. The main window sets it.
    private volatile Consumer<Event> eventSink;

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Life cycle step 1, before any window exists and NOT on the JavaFX thread: the place for slow
     * preparation. Here: read the command line and connect to the server.
     */
    @Override
    public void init() {
        Map<String, String> named = getParameters().getNamed();
        host = named.getOrDefault("host", "localhost");
        dataDir = Path.of(named.getOrDefault("data", "data"));
        try {
            port = Integer.parseInt(named.getOrDefault("port", String.valueOf(SkillExchangeServer.DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            port = SkillExchangeServer.DEFAULT_PORT;
        }
        serverUrl = named.get("server");
        dark = "dark".equals(named.getOrDefault("theme", preferences.get("theme", "light")));
        LogConfig.init(dataDir.resolve("logs"));
        // A server on the web may be asleep and take a minute to wake. That wait must not happen
        // here, before there is a window: start() shows a "connecting" screen and connects from there.
        if (serverUrl != null) return;
        try {
            connect();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not connect at start-up", e);
            startupError = Ui.message(e);
        }
    }

    /** Life cycle step 2, on the JavaFX thread: build the window. */
    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("Skill Exchange");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        scene = new Scene(new VBox(), 1280, 860);
        scene.getStylesheets().add(STYLESHEET);
        stage.setScene(scene);
        if (startupError != null) showOffline(startupError);
        else if (connection == null) showConnecting();
        else showMain(null, "browse");
        stage.show();

        String shots = getParameters().getNamed().get("shots");
        if (shots != null) new Screenshots(this, Path.of(shots)).run();
    }

    /** Life cycle step 3, when the last window closes: release what init() and start() acquired. */
    @Override
    public void stop() {
        if (main != null) main.dispose();
        RemoteApi c = connection;
        if (c != null) c.close();
        SkillExchangeServer s = embeddedServer;
        if (s != null) s.close();
    }

    Stage stage() { return stage; }
    Scene scene() { return scene; }
    RemoteApi connection() { return connection; }
    boolean isDark() { return dark; }
    void setEventSink(Consumer<Event> sink) { eventSink = sink; }

    /**
     * Opens the connection. Synchronized, so two background tasks cannot both start a server.
     * If nothing answers on this computer, a server is started here and the connection retried.
     */
    synchronized void connect() throws Exception {
        if (connection != null && !connection.isClosed()) return;
        RemoteApi fresh;
        if (serverUrl != null) {
            fresh = RemoteApi.connect(URI.create(serverUrl));
        } else try {
            fresh = RemoteApi.connect(host, port);
        } catch (IOException noServer) {
            if (!LOCAL.contains(host) || embeddedServer != null) throw new IOException(
                    "No Skill Exchange server answers at " + host + ":" + port, noServer);
            LOG.info("No server found on this computer, starting one inside the application");
            embeddedServer = ServerMain.boot(dataDir, port, false);
            fresh = RemoteApi.connect(host, port);
        }
        fresh.onDisconnect(() -> Platform.runLater(() -> showOffline("The connection to the server was lost.")));
        // Events arrive on the reader thread. Platform.runLater moves them to the JavaFX thread,
        // the only thread that is allowed to change what is on screen.
        fresh.onEvent(event -> Platform.runLater(() -> {
            Consumer<Event> sink = eventSink;
            if (sink != null) sink.accept(event);
        }));
        connection = fresh;
    }

    /** Shows the application for this student, or for a visitor if {@code me} is null, on the given page. */
    void showMain(Student me, String tab) {
        if (main != null) main.dispose();
        main = new MainView(new AppContext(this, connection.api(), me), tab);
        setRoot(main);
    }

    MainView main() { return main; }

    /** Shown while the first connection to a server on the web is being made. */
    private void showConnecting() {
        VBox box = new VBox(14, Ui.heading("Skill Exchange", "h1"),
                Ui.wrapped("Connecting to " + serverUrl + " ...", "muted"),
                Ui.wrapped("If the server was asleep, waking it can take about a minute.", "subtle", "small"));
        box.setAlignment(Pos.CENTER);
        setRoot(box);
        Ui.async(() -> {
            connect();
            return true;
        }, ok -> showMain(null, "browse"), error -> showOffline(Ui.message(error)));
    }

    /** Shown instead of the application while there is no server to talk to. */
    private void showOffline(String message) {
        if (scene == null) return;
        if (main != null) main.dispose();
        main = null;
        eventSink = null;
        Button retry = Ui.btn("Try again", "primary");
        retry.setOnAction(e -> {
            retry.setDisable(true);
            Ui.async(() -> {
                connect();
                return true;
            }, ok -> showMain(null, "browse"), error -> {
                retry.setDisable(false);
                Ui.alert(stage, "Still no connection", Ui.message(error));
            });
        });
        VBox box = new VBox(14, Ui.heading("Skill Exchange", "h1"), Ui.wrapped(message, "muted"), retry);
        box.setAlignment(Pos.CENTER);
        setRoot(box);
    }

    private void setRoot(Parent root) {
        root.getStyleClass().add("app");
        styleRoot(root);
        scene.setRoot(root);
    }

    /** Applies the theme and the heading font to a root node: the main window's, or a second window's. */
    void styleRoot(Parent root) {
        root.getStyleClass().removeAll("dark", "rounded");
        if (dark) root.getStyleClass().add("dark");
        // Headings use a rounded typeface where the computer has one.
        if (Font.getFamilies().contains("Arial Rounded MT Bold")) root.getStyleClass().add("rounded");
    }

    void toggleTheme() {
        dark = !dark;
        preferences.put("theme", dark ? "dark" : "light");
        styleRoot(scene.getRoot());
    }
}
