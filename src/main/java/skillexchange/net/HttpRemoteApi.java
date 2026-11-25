package skillexchange.net;

import skillexchange.api.Event;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to the server through the web, with the HTTP client that ships with the JDK. Used when
 * the server sits behind a hosting service that carries only HTTP.
 *
 * Each API call is one POST. HTTP cannot push, so events are fetched by long polling: a background
 * thread keeps one GET open; the server answers it the moment something happens, and the thread
 * asks again straight away.
 */
final class HttpRemoteApi extends RemoteApi {
    private static final Logger LOG = Logger.getLogger(HttpRemoteApi.class.getName());

    private static final String SESSION_HEADER = "X-Session";
    private static final String GREETING = "Skill Exchange server";
    /** A free hosting plan puts an idle server to sleep; waking it can take about a minute. */
    private static final Duration WAKE_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(45);
    private static final int GONE = 410;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final URI base;
    private final String session;
    private final Thread poller;

    HttpRemoteApi(URI server) throws IOException {
        String text = server.toString();
        this.base = URI.create(text.endsWith("/") ? text : text + "/");
        try {
            // First make sure this really is our server (and wake it up if it was asleep).
            HttpResponse<String> status = http.send(HttpRequest.newBuilder(base).timeout(WAKE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (status.statusCode() != 200 || !status.body().startsWith(GREETING))
                throw new IOException("There is no Skill Exchange server at " + base);
            HttpResponse<Void> hello = http.send(post("hello", new byte[0]).build(), HttpResponse.BodyHandlers.discarding());
            session = hello.headers().firstValue(SESSION_HEADER)
                    .orElseThrow(() -> new IOException("The server at " + base + " did not start a session"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to " + base, e);
        }
        poller = new Thread(this::pollLoop, "event-poller");
        poller.setDaemon(true);
        poller.start();
    }

    private HttpRequest.Builder post(String path, byte[] body) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    @Override
    public String describe() {
        return base.toString();
    }

    @Override
    protected Frame.Reply exchange(Frame.Call call) throws IOException, InterruptedException, TimeoutException {
        HttpResponse<byte[]> response = http.send(post("rpc", Wire.toBytes(call)).header(SESSION_HEADER, session).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == GONE) {
            lost();         // the server restarted or ended our session
            throw new IOException("The session on the server has ended");
        }
        if (response.statusCode() != 200) throw new IOException("The server answered with status " + response.statusCode());
        return (Frame.Reply) Wire.fromBytes(response.body(), null);
    }

    /** Runs on the poller thread until the connection is closed or lost. */
    private void pollLoop() {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("events")).timeout(POLL_TIMEOUT)
                .header(SESSION_HEADER, session).GET().build();
        int failures = 0;
        while (!isClosed()) {
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == GONE) break;
                if (response.statusCode() != 200) throw new IOException("status " + response.statusCode());
                failures = 0;
                // The body is a list of events, empty if nothing happened during the wait.
                for (Object item : (List<?>) Wire.fromBytes(response.body(), null)) {
                    deliver((Event) item);
                }
            } catch (InterruptedException e) {
                return;             // close() interrupted the wait
            } catch (IOException | RuntimeException e) {
                // One failed request is not the end: networks hiccup. Three in a row is.
                if (++failures >= 3) {
                    LOG.log(Level.FINE, "Giving up on " + base + " after repeated failures", e);
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
        lost();
    }

    @Override
    protected void release() {
        if (poller != null) poller.interrupt();
        // Tell the server it can forget this session. Best effort: nobody waits for the answer.
        http.sendAsync(post("bye", new byte[0]).header(SESSION_HEADER, session).build(), HttpResponse.BodyHandlers.discarding());
    }
}
