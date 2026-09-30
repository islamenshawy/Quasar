package com.cms.iso;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Packs / unpacks BASE24 ISO 8583:1993 messages and handles the TCP framing:
 *   [length prefix][static header][ISO message]
 * The length covers header + message. Prefix types: BINARY2 (2-byte big-endian, BASE24 default)
 * or ASCII4 (4 ASCII digits). The header is echoed unchanged in responses.
 * Layout lives in resources/iso/base24-1993.xml (provisional until IN-01).
 */
@Component
public class IsoCodec {

    public enum LengthPrefix { BINARY2, ASCII4 }

    private final GenericPackager packager;
    private final LengthPrefix prefix;
    private final int headerLength;

    public IsoCodec(@Value("${cms.iso.packager:iso/base24-1993.xml}") String packagerResource,
                    @Value("${cms.iso.length-prefix:BINARY2}") LengthPrefix prefix,
                    @Value("${cms.iso.header-length:0}") int headerLength) throws ISOException, IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(packagerResource)) {
            if (in == null) throw new IllegalStateException("ISO packager not found: " + packagerResource);
            this.packager = new GenericPackager(in);
        }
        this.prefix = prefix;
        this.headerLength = headerLength;
    }

    /** A framed message: header bytes (possibly empty) and the parsed ISO message. */
    public record Frame(byte[] header, ISOMsg msg) {}

    public ISOMsg newMessage() {
        ISOMsg m = new ISOMsg();
        m.setPackager(packager);
        return m;
    }

    public byte[] pack(ISOMsg m) throws ISOException {
        m.setPackager(packager);
        return m.pack();
    }

    public ISOMsg unpack(byte[] data) throws ISOException {
        ISOMsg m = newMessage();
        m.unpack(data);
        return m;
    }

    /** Reads one framed message; returns null at end of stream. */
    public byte[] readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            if (prefix == LengthPrefix.BINARY2) {
                len = in.readUnsignedShort();
            } else {
                byte[] l = in.readNBytes(4);
                if (l.length < 4) return null;
                len = Integer.parseInt(new String(l, StandardCharsets.US_ASCII));
            }
        } catch (EOFException e) {
            return null;
        }
        if (len <= 0 || len > 16384) throw new IOException("bad frame length " + len);
        byte[] data = in.readNBytes(len);
        if (data.length < len) return null;
        return data;
    }

    public void writeFrame(OutputStream out, byte[] header, byte[] iso) throws IOException {
        int len = header.length + iso.length;
        if (prefix == LengthPrefix.BINARY2) {
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(String.format("%04d", len).getBytes(StandardCharsets.US_ASCII));
        }
        out.write(header);
        out.write(iso);
        out.flush();
    }

    public Frame split(byte[] frame) throws ISOException {
        byte[] header = java.util.Arrays.copyOfRange(frame, 0, headerLength);
        byte[] iso = java.util.Arrays.copyOfRange(frame, headerLength, frame.length);
        return new Frame(header, unpack(iso));
    }

    public int headerLength() {
        return headerLength;
    }

    /** One-line summary for logs: MTI, STAN, processing code, amount, action code; PAN masked, no PIN/track/keys. */
    public static String summary(ISOMsg m) {
        try {
            String pan = m.getString(2);
            String masked = pan == null || pan.length() < 10 ? "-" : pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
            return m.getMTI() + " stan=" + m.getString(11) + " pc=" + m.getString(3) + " pan=" + masked
                    + " amt=" + m.getString(4) + " ccy=" + m.getString(49) + " term=" + m.getString(41)
                    + " fc=" + m.getString(24) + " ac=" + m.getString(39);
        } catch (ISOException e) {
            return "?";
        }
    }
}
