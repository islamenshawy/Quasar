package com.cms.hsm;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One TCP connection to a payShield 10K host port.
 *
 * Wire format (host command interface, TCP):
 *   [2-byte big-endian length][header (N chars)][command code (2)][fields...]
 * Response:
 *   [2-byte length][header echoed][response code (2)][error code (2)][fields...]
 *
 * Header length is a payShield config value (commonly 4). We send a rolling
 * counter as the header and check it is echoed back, so a stale/out-of-order
 * response on a reused socket is detected instead of silently mis-matched.
 *
 * Not thread-safe by itself; the pool in PayShieldClient hands one connection
 * to one caller at a time.
 */
final class PayShieldConnection implements Closeable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final int headerLength;
    private final AtomicInteger counter = new AtomicInteger();

    PayShieldConnection(String host, int port, int headerLength,
                        int connectTimeoutMs, int readTimeoutMs) throws IOException {
        this.headerLength = headerLength;
        this.socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /** Sends "commandCode + body", returns the response WITHOUT the header. */
    String exchange(String commandAndBody) throws IOException {
        String header = nextHeader();
        byte[] payload = (header + commandAndBody).getBytes(StandardCharsets.US_ASCII);
        if (payload.length > 0xFFFF) {
            throw new IllegalArgumentException("HSM message too long: " + payload.length);
        }
        out.writeShort(payload.length);
        out.write(payload);
        out.flush();

        int len = in.readUnsignedShort();
        byte[] resp = in.readNBytes(len);
        if (resp.length != len) {
            throw new EOFException("HSM closed connection mid-response");
        }
        String s = new String(resp, StandardCharsets.US_ASCII);
        if (s.length() < headerLength + 4) {
            throw new IOException("HSM response too short");
        }
        String echoed = s.substring(0, headerLength);
        if (!echoed.equals(header)) {
            throw new IOException("HSM header mismatch: sent " + header + " got " + echoed);
        }
        return s.substring(headerLength);
    }

    boolean isUsable() {
        return socket.isConnected() && !socket.isClosed();
    }

    private String nextHeader() {
        if (headerLength == 0) return "";
        int mod = (int) Math.pow(10, headerLength);
        int n = Math.floorMod(counter.incrementAndGet(), mod);
        return String.format("%0" + headerLength + "d", n);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
