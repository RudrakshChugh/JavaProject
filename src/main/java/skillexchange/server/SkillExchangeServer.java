package skillexchange.server;

import skillexchange.service.Services;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The network side of the back end. Clients reach it in one of two ways:
 * a ServerSocket that accepts connections and gives each one to a thread from a pool, and an
 * {@link HttpGateway} for clients that can only come in through the web.
 * All of them share the same {@link Services}, which is why the services have to be thread-safe.
 */
public final class SkillExchangeServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SkillExchangeServer.class.getName());

    public static final int DEFAULT_PORT = 5050;
    public static final int NO_PORT = -1;

    private final Services services;
    private final ServerSocket serverSocket;        // null if clients only come in over HTTP
    private final HttpGateway gateway;              // null if clients only come in by socket
    private final EventHub hub = new EventHub();
    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final ExecutorService clients = Executors.newCachedThreadPool(new NamedThreads("client"));
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(new NamedThreads("housekeeping"));
    private volatile boolean running;

    /**
     * Gives the pool's threads readable names ("client-3") and makes them daemon threads, so they
     * never keep the program alive on their own. A static nested class implementing an interface.
     */
    private static final class NamedThreads implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        NamedThreads(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + next.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /** Opens the socket port. Port 0 asks the operating system for any free port (used by the tests). */
    public SkillExchangeServer(Services services, int port) throws IOException {
        this(services, port, NO_PORT);
    }

    /**
     * Opens the socket port, the HTTP port, or both. Pass {@link #NO_PORT} for the one that is not
     * wanted: a hosting service that only carries HTTP needs no socket port.
     */
    public SkillExchangeServer(Services services, int port, int httpPort) throws IOException {
        this.services = services;
        this.serverSocket = port == NO_PORT ? null : new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        this.gateway = httpPort == NO_PORT ? null : new HttpGateway(services, hub, httpPort);
    }

    public Services services() { return services; }
    /** The socket port, or NO_PORT if this server has none. */
    public int getPort() { return serverSocket == null ? NO_PORT : serverSocket.getLocalPort(); }
    /** The HTTP port, or NO_PORT if this server has none. */
    public int getHttpPort() { return gateway == null ? NO_PORT : gateway.getPort(); }
    public int connectedClients() { return hub.connections(); }

    public void start() {
        running = true;
        if (serverSocket != null) {
            Thread acceptor = new Thread(this::acceptLoop, "acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            LOG.info(() -> "Server listening for sockets on port " + getPort());
        }
        if (gateway != null) gateway.start();
        // Twice a minute, on its own thread: forget sign-in pauses that have run out, and end
        // HTTP sessions whose client has gone away.
        housekeeping.scheduleAtFixedRate(() -> {
            int forgotten = services.accounts().forgetExpiredLocks();
            int ended = gateway == null ? 0 : gateway.expireIdle();
            if (forgotten + ended > 0) LOG.fine(() -> "Housekeeping: forgot " + forgotten
                    + " expired sign-in pauses, ended " + ended + " idle HTTP sessions");
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();      // waits here until a client connects
                socket.setTcpNoDelay(true);
                // An array of one, so the lambda below can refer to the handler it belongs to.
                ClientHandler[] holder = new ClientHandler[1];
                holder[0] = new ClientHandler(socket, services, hub, () -> handlers.remove(holder[0]));
                handlers.add(holder[0]);
                clients.execute(holder[0]);
            } catch (SocketException e) {
                if (running) LOG.log(Level.WARNING, "Accepting a connection failed", e);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Accepting a connection failed", e);
            }
        }
    }

    /** Stops accepting, closes every client connection and stops the threads. */
    @Override
    public void close() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();     // makes accept() throw, which ends the accept loop
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing the server socket failed", e);
        }
        if (gateway != null) gateway.close();
        handlers.forEach(ClientHandler::closeQuietly);
        housekeeping.shutdownNow();
        clients.shutdownNow();
        hub.shutdown();
        LOG.info("Server stopped");
    }
}
