package skillexchange.net;

import skillexchange.api.Event;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.ConnectionLostException;
import skillexchange.exception.SkillExchangeException;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The client's end of the connection to the server.
 *
 * {@link #api()} returns an object that implements {@link SkillExchangeApi} although no class in
 * the client does: a dynamic proxy. Whatever method is called on it arrives in {@link #invoke},
 * which sends the method's name and arguments to the server and returns the reply.
 *
 * This class is abstract: it knows what to send, not how. The two subclasses carry the same
 * frames in two ways, {@link SocketRemoteApi} down an open socket and {@link HttpRemoteApi} as
 * HTTP requests. The rest of the application never knows which one it has.
 */
public abstract class RemoteApi implements InvocationHandler, AutoCloseable {
    private final SkillExchangeApi proxy;
    private final AtomicLong nextId = new AtomicLong(1);
    // Listeners are added rarely and walked on every event, which is what CopyOnWriteArrayList is for.
    private final List<Consumer<Event>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectListeners = new CopyOnWriteArrayList<>();
    // AtomicBoolean: "close once" must be decided by exactly one of the threads that may notice the loss.
    private final AtomicBoolean closed = new AtomicBoolean();

    // The proxy only keeps the reference to this object; it calls nothing until an API method is used,
    // by which time the subclass is fully built. So handing out "this" here is safe.
    @SuppressWarnings("this-escape")
    protected RemoteApi() {
        this.proxy = (SkillExchangeApi) Proxy.newProxyInstance(
                SkillExchangeApi.class.getClassLoader(), new Class<?>[] {SkillExchangeApi.class}, this);
    }

    /** Connects straight to a server's socket port. */
    public static RemoteApi connect(String host, int port) throws IOException {
        return new SocketRemoteApi(host, port);
    }

    /** Connects through the web, to a server's HTTP address such as https://example.onrender.com. */
    public static RemoteApi connect(URI server) throws IOException {
        return new HttpRemoteApi(server);
    }

    /** The API, as if the server's object were here. */
    public final SkillExchangeApi api() { return proxy; }

    public final void onEvent(Consumer<Event> listener) { eventListeners.add(listener); }

    public final void onDisconnect(Runnable listener) { disconnectListeners.add(listener); }

    public final boolean isClosed() { return closed.get(); }

    /** Where this is connected to, for messages. */
    public abstract String describe();

    /** Sends one call and waits for its reply. Each subclass does this its own way. */
    protected abstract Frame.Reply exchange(Frame.Call call) throws IOException, InterruptedException, TimeoutException;

    /** Lets go of the socket, threads or session. Called once. */
    protected abstract void release();

    /** Called by the proxy for every API method. */
    @Override
    public final Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {       // toString, equals, hashCode
            return switch (method.getName()) {
                case "toString" -> "RemoteApi[" + describe() + "]";
                case "hashCode" -> System.identityHashCode(proxyObject);
                default -> proxyObject == args[0];
            };
        }
        if (isClosed()) throw new ConnectionLostException("Not connected to the server", null);

        Frame.Reply reply;
        try {
            reply = exchange(new Frame.Call(nextId.getAndIncrement(), method.getName(), args));
        } catch (TimeoutException e) {
            throw new ConnectionLostException("The server did not answer in time", e);
        } catch (IOException e) {
            throw new ConnectionLostException("The connection to the server was lost", e);
        }
        if (reply.error() == null) return reply.value();
        // The server answered with an exception: throw that one, as if the method had run here.
        Throwable error = reply.error();
        if (error instanceof SkillExchangeException || error instanceof RuntimeException) throw error;
        throw new ConnectionLostException("Unexpected answer from the server", error);
    }

    /** For subclasses: hand an event from the server to everyone who is listening. */
    protected final void deliver(Event event) {
        for (Consumer<Event> listener : eventListeners) {
            listener.accept(event);
        }
    }

    /** For subclasses: the connection ended without close() being called. */
    protected final void lost() {
        // compareAndSet makes sure only the first caller goes on, however many threads notice the loss.
        if (closed.compareAndSet(false, true)) {
            release();
            disconnectListeners.forEach(Runnable::run);
        }
    }

    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) release();
    }
}
