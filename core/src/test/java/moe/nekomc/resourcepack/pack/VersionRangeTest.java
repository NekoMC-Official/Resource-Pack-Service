package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionRangeTest {

    @Test
    void wildcardAcceptsAnyVersion() {
        assertTrue(VersionRange.parse("").accepts("26.3"));
        assertTrue(VersionRange.parse("*").accepts("1.20.4"));
    }

    @Test
    void exactVersionIgnoresMissingSegments() {
        assertTrue(VersionRange.parse("1.20").accepts("1.20.0"));
        assertFalse(VersionRange.parse("1.20").accepts("1.20.1"));
    }

    @Test
    void doubleEqualsIsExactMatch() {
        assertTrue(VersionRange.parse("==1.20").accepts("1.20.0"));
        assertFalse(VersionRange.parse("==1.20").accepts("1.20.1"));
        assertTrue(VersionRange.parse("==1.20.4").accepts("1.20.4"));
        assertFalse(VersionRange.parse("==1.20.4").accepts("1.20.5"));
        assertTrue(VersionRange.parse("==1.2").accepts("1.2.0"));
    }

    @Test
    void comparisonRangeUsesAnd() {
        VersionRange range = VersionRange.parse(">=1.20 <1.21");
        assertTrue(range.accepts("1.20.4"));
        assertFalse(range.accepts("1.21"));
    }

    @Test
    void commaUsesOr() {
        VersionRange range = VersionRange.parse("26.3, 1.21");
        assertTrue(range.accepts("1.21"));
        assertTrue(range.accepts("26.3"));
        assertFalse(range.accepts("1.21.1"));
        assertFalse(range.accepts("1.20"));
    }

    @Test
    void suffixAndPrefixAreIgnored() {
        assertTrue(VersionRange.parse("v1.20+abc").accepts("1.20.0"));
        assertTrue(VersionRange.parse(">=1.20-rc1").accepts("1.20.5"));
    }

    @Test
    void invalidExpressionReturnsNull() {
        assertNull(VersionRange.parse("abc"));
        assertNull(VersionRange.parse(">=1.0 <"));
        assertFalse(VersionRange.parse(">=1.0").accepts("not-a-version"));
    }
}
