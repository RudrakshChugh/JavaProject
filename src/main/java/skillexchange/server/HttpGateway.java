package skillexchange.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import skillexchange.api.Event;
import skillexchange.net.Frame;
import skillexchange.net.Wire;
import skillexchange.service.Services;
import skillexchange.web.WebSite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A second door into the same server, for clients that can only reach it through the web
 * (a hosting service that carries HTTP and nothing else). It uses the HTTP server that ships
 * with the JDK. For the desktop application the bodies of the requests are the same serialized
 * {@link Frame} objects the socket carries.
 *
 * <pre>
 * POST /hello    start a session; the answer carries its id in the X-Session header
 * POST /rpc      body: a Frame.Call     answer: a Frame.Reply
 * GET  /events   waits up to 25 seconds for events, then answers with a list (long polling)
 * POST /bye      end the session
 * GET  /status   a few lines of text saying the server is up
 * </pre>
 *
 * Those five addresses are for the desktop application. Every other address is a page of the
 * web site ({@link WebSite}), which the same HTTP server answers for people using a browser.
 *
 * HTTP has no lasting connection, so "who is signed in" cannot live in a connection as it does
 * with a socket. Each client gets a session here, found again by the id it sends with every request.
 */
final class HttpGateway implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HttpGateway.class.getName());

    static final String SESSION_HEADER = "X-Session";
    /** The first words of the status page. The client checks for them to know it found the right server. */
    static final String GREETING = "Skill Exchange server";
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int POLL_SECONDS = 25;
    private static final long IDLE_MILLIS = 90_000;
    private static final int GONE = 410;

    private final Services services;
    private final EventHub hub;
    private final HttpServer http;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final WebSite site;

    /** One client's session: its API object and the events waiting to be collected. An inner class. */
    private final class WebSession implements EventHub.Sink {
        final String id;
        final ApiSession api;
        // Producer and consumer: the notifier thread puts events in, the client's waiting request takes them out.
        final BlockingQueue<Event> waiting = new LinkedBlockingQueue<>(500);
        volatile long lastSeen = System.currentTimeMillis();

        WebSession(String id) {
            this.id = id;
            this.api = new ApiSession(services, hub, this);
        }

        @Override
        public void push(Event event) {
            waiting.offer(event);       // offer() never blocks; if the queue is full the event is dropped
        }

        void end() {
            hub.disconnect(this);
            api.close();
        }
    }

    HttpGateway(Services services, EventHub hub, int port) throws IOException {
        this.services = services;
        this.hub = hub;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/status", this::status);
        http.createContext("/hello", exchange -> guarded(exchange, this::hello));
        http.createContext("/rpc", exchange -> guarded(exchange, this::rpc));
        http.createContext("/events", exchange -> guarded(exchange, this::events));
        http.createContext("/bye", exchange -> guarded(exchange, this::bye));
        // Every other address belongs to the web site. A visitor in a browser gets an API object of
        // the same class as a desktop client's; it only has nobody to push events to.
        this.site = new WebSite(() -> new ApiSession(services, hub, event -> { }));
        site.attach(http);
        // A waiting /events request holds its thread for up to 25 seconds, so the pool must be able to grow.
        AtomicInteger threadNo = new AtomicInteger(1);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-" + threadNo.getAndIncrement());
            t.setDaemon(true);
            return t;
        }));
    }

    void start() {
        http.start();
        LOG.info(() -> "HTTP gateway listening on port " + getPort());
    }

    int getPort() {
        return http.getAddress().getPort();
    }

    int sessionCount() {
        return sessions.size();
    }

    /** Ends sessions whose client has stopped asking for events. Called by the housekeeping thread. */
    int expireIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_MILLIS;
        int ended = 0;
        for (WebSession session : sessions.values()) {
            // remove(key, value) only removes if the entry is still this session: safe against a race.
            if (session.lastSeen < cutoff && sessions.remove(session.id, session)) {
                session.end();
                ended++;
            }
        }
        return ended + site.expireIdle();
    }

    @Override
    public void close() {
        http.stop(0);
        sessions.values().forEach(WebSession::end);
        sessions.clear();
    }

    // ---------- Handlers ----------

    /** A request handler that may throw. A nested functional interface. */
    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** Runs a handler, and makes sure the exchange is always answered and closed. */
    private void guarded(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.handle(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "HTTP request " + exchange.getRequestURI() + " failed", e);
            if (exchange.getResponseCode() == -1) respond(exchange, 400, new byte[0]);
        } finally {
            exchange.close();       // whatever happened, release the connection
        }
    }

    private void status(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String text = GREETING + " is running.\n\nThe web site is at https://" + host + "/\n"
                    + "The desktop application connects with:\n\n    java -jar skill-exchange.jar --server=https://" + host + "\n";
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            respond(exchange, 200, text.getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private void hello(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        byte[] id = new byte[24];
        random.nextBytes(id);
        WebSession session = new WebSession(HexFormat.of().formatHex(id));
        sessions.put(session.id, session);
        hub.connect(session);
        exchange.getResponseHeaders().set(SESSION_HEADER, session.id);
        respond(exchange, 200, new byte[0]);
    }

    private void rpc(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        WebSession session = session(exchange);
        if (session == null) return;
        byte[] body = readBody(exchange);
        if (body == null) return;
        if (!(Wire.fromBytes(body, Dispatcher.FILTER) instanceof Frame.Call call)) {
            respond(exchange, 400, new byte[0]);
            return;
        }
        respond(exchange, 200, Wire.toBytes(Dispatcher.handle(session.api, call)));
    }

    /** Long polling: the answer is held back until there is an event to send, or 25 seconds have passed. */
    private void events(HttpExchange exchange) throws IOException, InterruptedException {
        WebSession session = session(exchange);
        if (session == null) return;
        ArrayList<Event> batch = new ArrayList<>();
        Event first = session.waiting.poll(POLL_SECONDS, TimeUnit.SECONDS);     // waits here
        if (first != null) {
            batch.add(first);
            session.waiting.drainTo(batch);         // and any others that arrived with it
        }
        session.lastSeen = System.currentTimeMillis();
        respond(exchange, 200, Wire.toBytes(batch));
    }

    private void bye(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        WebSession session = id == null ? null : sessions.remove(id);
        if (session != null) session.end();
        respond(exchange, 200, new byte[0]);
    }

    // ---------- Helpers ----------

    /** The session this request belongs to. Answers "410 Gone" and returns null if there is none. */
    private WebSession session(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        WebSession session = id == null ? null : sessions.get(id);
        if (session == null) {
            respond(exchange, GONE, new byte[0]);
            return null;
        }
        session.lastSeen = System.currentTimeMillis();
        return session;
    }

    private static boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) return true;
        respond(exchange, 405, new byte[0]);
        return false;
    }

    /** Reads the request body, refusing one that is larger than any real call could be. */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                respond(exchange, 413, new byte[0]);
                return null;
            }
            return body;
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        // A length of -1 tells the JDK server that there is no body at all.
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
