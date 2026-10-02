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
}
