package skillexchange.net;

import skillexchange.exception.ConnectionLostException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to the server down one open socket. A background thread reads everything the server
 * sends: a reply completes the future its caller is waiting on, an event goes to the listeners.
 */
final class SocketRemoteApi extends RemoteApi {
    private static final Logger LOG = Logger.getLogger(SocketRemoteApi.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int REPLY_TIMEOUT_SECONDS = 30;

    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    // Calls that are waiting for their reply, by call id. Written by callers, completed by the reader thread.
    private final Map<Long, CompletableFuture<Frame.Reply>> waiting = new ConcurrentHashMap<>();

    SocketRemoteApi(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush();
            in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        Thread reader = new Thread(this::readLoop, "server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public String describe() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    protected Frame.Reply exchange(Frame.Call call) throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<Frame.Reply> reply = new CompletableFuture<>();
        waiting.put(call.id(), reply);
        try {
            send(call);
            return reply.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new ConnectionLostException("The connection to the server was lost", e.getCause());
        } finally {
            waiting.remove(call.id());
        }
    }

    /** Synchronized: several threads of the client may call the API at the same time. */
    private synchronized void send(Frame frame) throws IOException {
        out.writeObject(frame);
        out.reset();
        out.flush();
    }

    private void readLoop() {
        try {
            while (true) {
                Object received = in.readObject();
                if (received instanceof Frame.Reply reply) {
                    CompletableFuture<Frame.Reply> future = waiting.get(reply.id());
                    if (future != null) future.complete(reply);     // null: the caller gave up waiting
                } else if (received instanceof Frame.Push push) {
                    deliver(push.event());
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (!isClosed()) LOG.log(Level.FINE, "Connection to the server ended", e);
        } finally {
            lost();
        }
    }

    @Override
    protected void release() {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing the socket failed", e);
        }
        // Nobody will answer the calls still waiting: wake them with an exception.
        IOException gone = new IOException("The connection to the server was closed");
        waiting.values().forEach(f -> f.completeExceptionally(gone));
    }
}
