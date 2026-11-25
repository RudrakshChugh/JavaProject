package skillexchange.server;

import skillexchange.api.Event;
import skillexchange.net.Frame;
import skillexchange.service.Services;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves one client that connected by socket, on its own thread: reads a call, runs it, writes
 * the reply, repeat. Events for this client are written down the same socket.
 */
final class ClientHandler implements Runnable, EventHub.Sink {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final EventHub hub;
    private final ApiSession session;
    private final Runnable onClose;
    private ObjectOutputStream out;

    ClientHandler(Socket socket, Services services, EventHub hub, Runnable onClose) {
        this.socket = socket;
        this.hub = hub;
        this.session = new ApiSession(services, hub, this);
        this.onClose = onClose;
    }

    @Override
    public void run() {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        LOG.fine(() -> "Client connected from " + peer);
        try (socket) {
            // The output stream must be created and flushed first: the other side's ObjectInputStream
            // waits for its header before its constructor returns.
            out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush();
            ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
            in.setObjectInputFilter(Dispatcher.FILTER);
            hub.connect(this);

            while (true) {
                Object received = in.readObject();
                if (!(received instanceof Frame.Call call))
                    throw new IOException("Expected a call, got " + received.getClass().getName());
                send(Dispatcher.handle(session, call));
            }
        } catch (EOFException | SocketException e) {
            LOG.fine(() -> "Client " + peer + " (" + session.describeUser() + ") disconnected");
        } catch (IOException | ClassNotFoundException e) {
            LOG.log(Level.WARNING, "Connection to " + peer + " closed after an error", e);
        } finally {
            hub.disconnect(this);
            session.close();
            onClose.run();
        }
    }

    /**
     * Writes one frame. Synchronized, because two threads write to this socket: this connection's
     * own thread (replies) and the notifier thread (events). Without it their bytes could interleave.
     */
    private synchronized void send(Frame frame) throws IOException {
        out.writeObject(frame);
        out.reset();        // forget what was sent, so an object that changes later is sent afresh
        out.flush();
    }

    @Override
    public void push(Event event) throws IOException {
        if (out == null) return;
        send(new Frame.Push(event));
    }

    void closeQuietly() {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing a client socket failed", e);
        }
    }
}
