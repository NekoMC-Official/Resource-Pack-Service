package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SourceFingerprintTest {

    @Test
    void sameInputsProduceSameDigest() {
        String first = SourceFingerprint.create().add("a").add("1").add("x.zip").hex();
        String second = SourceFingerprint.create().add("a").add("1").add("x.zip").hex();
        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void fieldOrderAndContentMatter() {
        String left = SourceFingerprint.create().add("a").add("b").hex();
        String right = SourceFingerprint.create().add("b").add("a").hex();
        assertNotEquals(left, right);
        assertNotEquals(left, SourceFingerprint.create().add("ab").hex());
    }

    @Test
    void nullIsTreatedAsEmpty() {
        String withNull = SourceFingerprint.create().add("a").add(null).hex();
        String withEmpty = SourceFingerprint.create().add("a").add("").hex();
        assertEquals(withNull, withEmpty);
        assertNotEquals(withNull, SourceFingerprint.create().add("a").hex());
    }
}
