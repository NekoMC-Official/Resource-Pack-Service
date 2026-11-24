package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class Sha1HexTest {

    @Test
    void parsesFortyHexDigits() {
        byte[] raw = Sha1Hex.toBytes("00112233445566778899aabbccddeeff00112233");
        assertEquals(20, raw.length);
        assertEquals(0x00, raw[0]);
        assertEquals((byte) 0xee, raw[14]);
        assertEquals((byte) 0xff, raw[15]);
        assertEquals(0x00, raw[16]);
        assertEquals(0x33, raw[19]);
    }

    @Test
    void acceptsUpperCase() {
        byte[] raw = Sha1Hex.toBytes("AABBCCDDEEFF00112233445566778899AABBCCDD");
        assertArrayEquals(Sha1Hex.toBytes("aabbccddeeff00112233445566778899aabbccdd"), raw);
    }

    @Test
    void rejectsWrongShape() {
        assertNull(Sha1Hex.toBytes(null));
        assertNull(Sha1Hex.toBytes(""));
        assertNull(Sha1Hex.toBytes("abc"));
        assertNull(Sha1Hex.toBytes("zz112233445566778899aabbccddeeff00112233"));
        assertNull(Sha1Hex.toBytes("00112233445566778899aabbccddeeff0011223344"));
    }

    @Test
    void quotesEtag() {
        assertEquals("\"abc\"", Sha1Hex.quote("abc"));
        assertEquals("\"\"", Sha1Hex.quote(null));
    }
}
