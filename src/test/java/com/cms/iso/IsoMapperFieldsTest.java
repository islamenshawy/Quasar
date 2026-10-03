package com.cms.iso;

import com.cms.auth.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IsoMapperFieldsTest {

    @Test
    void entryModeFromPosDataPosition7() {
        assertEquals("CHIP", IsoMapper.entryMode("210101510000", Channel.POS));
        assertEquals("CONTACTLESS", IsoMapper.entryMode("210101M10000", Channel.POS));
        assertEquals("MAGSTRIPE", IsoMapper.entryMode("210101210000", Channel.ATM));
        assertEquals("ECOM", IsoMapper.entryMode("100010000000", Channel.ECOM));
        assertNull(IsoMapper.entryMode(null, Channel.POS));
        assertNull(IsoMapper.entryMode("2101", Channel.POS));
    }

    @Test
    void cvv2FromField48() {
        assertEquals("123", IsoMapper.cvv2("XYCV2123"));
        assertEquals("1234", IsoMapper.cvv2("CV21234"));
        assertNull(IsoMapper.cvv2("CV2AB"));
        assertNull(IsoMapper.cvv2(null));
    }

    @Test
    void taggedValuesOfField48TogetherWithCvv2() {
        String cavv = "0101010795894" + "6ED67C9291A004BA492F6674286";
        String f48 = "CV2123TKN164895371234567897" + "CAV" + cavv + "ECI05";
        assertEquals("123", IsoMapper.cvv2(f48));
        assertEquals("4895371234567897", IsoMapper.token(f48));
        assertEquals(cavv, IsoMapper.tagged(f48, "CAV([0-9A-Fa-f]{40})"));
        assertEquals("05", IsoMapper.tagged(f48, "ECI([0-9]{2})"));
        assertNull(IsoMapper.token("TKN09123456789"));      // token numbers are 13-19 digits
        assertNull(IsoMapper.token("TKN161234"));            // shorter than announced
        assertNull(IsoMapper.tagged("CAV0101", "CAV([0-9A-Fa-f]{40})"));
    }
}
