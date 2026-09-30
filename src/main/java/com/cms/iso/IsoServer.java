package com.cms.iso;

import com.cms.auth.AuthRequest;
import com.cms.auth.AuthResponse;
import com.cms.auth.AuthorizationService;
import org.jpos.iso.ISOMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server for the ATM / POS switch (BASE24). One reader per connection; every request is
 * processed on its own virtual thread, so a slow HSM call never blocks the other messages on
 * the link, and responses may go back out of order (the switch matches them by STAN).
 */
@Component
public class IsoServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IsoServer.class);

    private final IsoCodec codec;
    private final AuthorizationService auth;
    private final NetworkManagementService network;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int port;

    private volatile boolean running;
    private ServerSocket server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, String> numericByAlpha = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();

    public IsoServer(IsoCodec codec, AuthorizationService auth, NetworkManagementService network, JdbcTemplate jdbc,
                     @Value("${cms.iso.enabled:true}") boolean enabled, @Value("${cms.iso.port:7000}") int port) {
        this.codec = codec;
        this.auth = auth;
        this.network = network;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.port = port;
    }

    public record Status(boolean enabled, boolean running, int port, int connections, long received) {}

    public Status status() {
        return new Status(enabled, running, port, connections.size(), received.get());
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("ISO server disabled (cms.iso.enabled=false)");
            return;
        }
        try {
            server = new ServerSocket(port);
        } catch (IOException e) {
            // keep the CMS up (console, Dexxis) even if the port is taken; status shows it
            log.error("ISO server cannot listen on port {}: {}", port, e.getMessage());
            return;
        }
        running = true;
        Thread.ofVirtual().name("iso-accept").start(this::acceptLoop);
        log.info("ISO server listening on {}", port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                connections.add(s);
                Thread.ofVirtual().name("iso-" + s.getRemoteSocketAddress()).start(() -> serve(s));
            } catch (IOException e) {
                if (running) log.warn("ISO accept failed: {}", e.getMessage());
            }
        }
    }

    private void serve(Socket s) {
        String peer = String.valueOf(s.getRemoteSocketAddress());
        log.info("Switch connected: {}", peer);
        try (s; DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()))) {
            OutputStream out = s.getOutputStream();
            byte[] frame;
            while ((frame = codec.readFrame(in)) != null) {
                received.incrementAndGet();
                byte[] f = frame;
                workers.submit(() -> handle(f, out, peer));
            }
        } catch (SocketException e) {
            log.info("Switch {} closed: {}", peer, e.getMessage());
        } catch (IOException e) {
            log.warn("Switch {} link error: {}", peer, e.getMessage());
        } finally {
            connections.remove(s);
            log.info("Switch disconnected: {}", peer);
        }
    }

    private void handle(byte[] frame, OutputStream out, String peer) {
        IsoCodec.Frame in;
        try {
            in = codec.split(frame);
        } catch (Exception e) {
            log.error("Unparseable message from {} ({} bytes): {}", peer, frame.length, e.getMessage());
            return;
        }
        try {
            ISOMsg req = in.msg();
            log.info("<- {}", IsoCodec.summary(req));
            ISOMsg resp;
            if (IsoMapper.isNetworkManagement(req)) {
                resp = network.handle(req, codec, peer);
            } else {
                AuthRequest ar = IsoMapper.toRequest(req);
                AuthResponse r = auth.authorize(ar);
                resp = IsoMapper.toResponse(req, r, codec, this::numericCurrency);
            }
            byte[] packed = codec.pack(resp);
            synchronized (out) {
                codec.writeFrame(out, in.header(), packed);
            }
            log.info("-> {}", IsoCodec.summary(resp));
        } catch (Exception e) {
            log.error("Failed to process message from {}: {}", peer, e.getMessage(), e);
        }
    }

    private String numericCurrency(String alpha) {
        if (alpha == null) return null;
        return numericByAlpha.computeIfAbsent(alpha, a -> jdbc.query(
                "SELECT numeric_code FROM currency WHERE code = ?", rs -> rs.next() ? rs.getString(1) : "000", a));
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Socket s : connections) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
