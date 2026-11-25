package skillexchange.net;

import skillexchange.api.Event;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.ConnectionLostException;
import skillexchange.exception.SkillExchangeException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's end of the connection.
 *
 * {@link #api()} returns an object that implements {@link SkillExchangeApi} although no class in
 * the client does: a dynamic proxy. Whatever method is called on it arrives in {@link #invoke},
 * which sends the method's name and arguments to the server and waits for the reply.
 *
 * A background thread reads everything the server sends. A reply completes the future the caller
 * is waiting on; an event is handed to the listeners.
 */
public final class RemoteApi implements InvocationHandler, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RemoteApi.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int REPLY_TIMEOUT_SECONDS = 30;

    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private final SkillExchangeApi proxy;
    private final AtomicLong nextId = new AtomicLong(1);
    // Calls that are waiting for their reply, by call id. Written by callers, completed by the reader thread.
    private final Map<Long, CompletableFuture<Object>> waiting = new ConcurrentHashMap<>();
    // Listeners are added rarely and walked on every event, which is what CopyOnWriteArrayList is for.
    private final List<Consumer<Event>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private RemoteApi(Socket socket) throws IOException {
        this.socket = socket;
        this.out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.out.flush();
        this.in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
        this.proxy = (SkillExchangeApi) Proxy.newProxyInstance(
                SkillExchangeApi.class.getClassLoader(), new Class<?>[] {SkillExchangeApi.class}, this);
        Thread reader = new Thread(this::readLoop, "server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public static RemoteApi connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            return new RemoteApi(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** The API, as if the server's object were here. */
    public SkillExchangeApi api() { return proxy; }

    public void onEvent(Consumer<Event> listener) { eventListeners.add(listener); }

    public void onDisconnect(Runnable listener) { disconnectListeners.add(listener); }

    public boolean isClosed() { return closed; }

    /** Called by the proxy for every API method. */
    @Override
    public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {       // toString, equals, hashCode
            return switch (method.getName()) {
                case "toString" -> "RemoteApi[" + socket.getRemoteSocketAddress() + "]";
                case "hashCode" -> System.identityHashCode(proxyObject);
                default -> proxyObject == args[0];
            };
        }
        if (closed) throw new ConnectionLostException("Not connected to the server", null);

        long id = nextId.getAndIncrement();
        CompletableFuture<Object> reply = new CompletableFuture<>();
        waiting.put(id, reply);
        try {
            send(new Frame.Call(id, method.getName(), args));
            return reply.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // The server answered with an exception: throw that one, as if the method had run here.
            Throwable cause = e.getCause();
            if (cause instanceof SkillExchangeException || cause instanceof RuntimeException) throw cause;
            throw new ConnectionLostException("Unexpected answer from the server", cause);
        } catch (TimeoutException e) {
            throw new ConnectionLostException("The server did not answer in time", e);
        } catch (IOException e) {
            throw new ConnectionLostException("The connection to the server was lost", e);
        } finally {
            waiting.remove(id);
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
                    CompletableFuture<Object> future = waiting.get(reply.id());
                    if (future == null) continue;                   // the caller gave up waiting
                    if (reply.error() != null) future.completeExceptionally(reply.error());
                    else future.complete(reply.value());
                } else if (received instanceof Frame.Push push) {
                    for (Consumer<Event> listener : eventListeners) {
                        listener.accept(push.event());
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (!closed) LOG.log(Level.FINE, "Connection to the server ended", e);
        } finally {
            boolean wasOpen = !closed;
            closed = true;
            // Nobody will answer the calls still waiting: wake them with an exception.
            ConnectionLostException lost = new ConnectionLostException("The connection to the server was lost", null);
            waiting.values().forEach(f -> f.completeExceptionally(lost));
            if (wasOpen) disconnectListeners.forEach(Runnable::run);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing the socket failed", e);
        }
    }
}
