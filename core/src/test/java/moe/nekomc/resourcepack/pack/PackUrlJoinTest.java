package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackUrlJoinTest {

    @Test
    void appendsGenerationAsQuery() {
        assertEquals("http://host/a.zip?v=3", PackPublisher.withVersion("http://host/a.zip", 3));
    }

    @Test
    void keepsExistingQuery() {
        assertEquals("http://host/a.zip?token=abc&v=2", PackPublisher.withVersion("http://host/a.zip?token=abc", 2));
    }

    @Test
    void blankUrlStaysBlank() {
        assertEquals("", PackPublisher.withVersion("   ", 1));
        assertEquals("", PackPublisher.withVersion(null, 9));
    }

    @Test
    void generationIsAtLeastOne() {
        assertEquals("http://host/a.zip?v=1", PackPublisher.withVersion("http://host/a.zip", 0));
    }
}
