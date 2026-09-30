package com.cms.emv;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal BER-TLV for EMV field 55: tags of 1-3 bytes, lengths of 1-3 bytes, primitive values only. */
public final class Tlv {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private Tlv() {}

    /** Tag (upper-case hex) -> value, in order of appearance. Later duplicates overwrite earlier ones. */
    public static Map<String, byte[]> parse(byte[] data) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length) {
            if (data[i] == 0x00 || data[i] == (byte) 0xFF) { i++; continue; }   // padding
            int start = i;
            if ((data[i++] & 0x1F) == 0x1F) {
                while (i < data.length && (data[i] & 0x80) != 0) i++;
                i++;
            }
            if (i > data.length) throw new IllegalArgumentException("truncated tag");
            String tag = HEX.formatHex(data, start, i);
            if (i >= data.length) throw new IllegalArgumentException("missing length for " + tag);
            int len = data[i++] & 0xFF;
            if (len > 0x80) {
                int n = len & 0x7F;
                if (n > 2 || i + n > data.length) throw new IllegalArgumentException("bad length for " + tag);
                len = 0;
                for (int k = 0; k < n; k++) len = (len << 8) | (data[i++] & 0xFF);
            }
            if (i + len > data.length) throw new IllegalArgumentException("truncated value for " + tag);
            byte[] v = new byte[len];
            System.arraycopy(data, i, v, 0, len);
            out.put(tag, v);
            i += len;
        }
        return out;
    }

    public static byte[] encode(Map<String, byte[]> tags) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (var e : tags.entrySet()) {
            b.writeBytes(HEX.parseHex(e.getKey()));
            int len = e.getValue().length;
            if (len > 0xFF) { b.write(0x82); b.write(len >> 8); b.write(len & 0xFF); }
            else if (len > 0x7F) { b.write(0x81); b.write(len); }
            else b.write(len);
            b.writeBytes(e.getValue());
        }
        return b.toByteArray();
    }
}
