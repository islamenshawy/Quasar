package com.cms.iso;

import com.cms.auth.AuthRequest;
import com.cms.auth.AuthResponse;
import com.cms.auth.Channel;
import com.cms.auth.TxnType;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsoMapperTest {

    private final IsoCodec codec = new IsoCodec("iso/base24-1993.xml", IsoCodec.LengthPrefix.BINARY2, 0);

    IsoMapperTest() throws Exception {
    }

    private ISOMsg base(String mti, String pc) throws Exception {
        ISOMsg m = codec.newMessage();
        m.setMTI(mti);
        m.set(2, "9999990000000014");
        m.set(3, pc);
        m.set(4, "000000010000");
        m.set(7, "1001120000");
        m.set(11, "000123");
        m.set(12, "261001150000");
        m.set(32, "123456");
        m.set(41, "ATM00001");
        m.set(49, "818");
        return m;
    }

    @Test
    void responseMtiKeepsOriginAndFoldsRepeats() {
        assertEquals("1210", IsoMapper.responseMti("1200"));
        assertEquals("1210", IsoMapper.responseMti("1201"));
        assertEquals("1430", IsoMapper.responseMti("1421"));
        assertEquals("1230", IsoMapper.responseMti("1220"));
        assertEquals("1110", IsoMapper.responseMti("1100"));
        assertEquals("1814", IsoMapper.responseMti("1804"));
    }

    @Test
    void mapsCashWithdrawalWithPinThroughPackAndUnpack() throws Exception {
        ISOMsg m = base("1200", "010000");
        m.set(52, ISOUtil.hex2byte("0123456789ABCDEF"));
        m.set(26, "6011");
        ISOMsg back = codec.unpack(codec.pack(m));

        AuthRequest r = IsoMapper.toRequest(back);
        assertEquals(TxnType.WITHDRAWAL, r.type());
        assertEquals(Channel.ATM, r.channel());
        assertEquals(10000, r.amount());
        assertEquals("0123456789ABCDEF", r.pinBlock());
        assertEquals("ATM00001", r.terminalId());
    }

    @Test
    void mapsEcommerceFromPosDataCode() throws Exception {
        ISOMsg m = base("1200", "000000");
        m.set(22, "100010000000");
        assertEquals(Channel.ECOM, IsoMapper.toRequest(m).channel());
        m.set(22, "210101210000");
        assertEquals(Channel.POS, IsoMapper.toRequest(m).channel());
    }

    @Test
    void mapsPartialReversalWithOriginalDataElements() throws Exception {
        ISOMsg m = base("1421", "010000");
        m.set(30, "000000010000000000010000");
        m.set(4, "000000004000");
        m.set(56, "1200000122261001150000" + "06123456");
        AuthRequest r = IsoMapper.toRequest(m);
        assertEquals(TxnType.REVERSAL, r.type());
        assertEquals("1420", r.mti());
        assertEquals(10000, r.amount());
        assertEquals(4000L, r.amountCompleted());
        assertEquals("1200", r.original().mti());
        assertEquals("000122", r.original().stan());
        assertEquals("261001150000", r.original().transmissionDt());
        assertEquals("123456", r.original().acquirerId());
    }

    @Test
    void completionIsA1220NamingA1100() throws Exception {
        ISOMsg m = base("1220", "000000");
        m.set(56, "1100000200261001150000" + "06123456");
        assertEquals(TxnType.COMPLETION, IsoMapper.toRequest(m).type());
    }

    @Test
    void responseCarriesApprovalAndBalances() throws Exception {
        ISOMsg req = base("1200", "310000");
        ISOMsg resp = IsoMapper.toResponse(req,
                new AuthResponse("000", "000042", "EGP", 150000L, -2500L, 1L, null), codec, a -> "818");
        assertEquals("1210", resp.getMTI());
        assertEquals("000042", resp.getString(38));
        assertEquals("000", resp.getString(39));
        assertEquals("0001818C000000150000" + "0002818D000000002500", resp.getString(54));
        assertNotNull(codec.pack(resp));
    }
}
