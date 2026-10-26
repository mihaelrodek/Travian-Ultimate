package com.travianbot.server.discovery;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test — no Spring, no DB. The generator is a pure function over its
 * static constants, so the test asserts the shape of its output rather than
 * delegating to a mock.
 */
class CandidateUrlGeneratorTest {

    private static final Pattern HTTPS_TRAVIAN = Pattern.compile("^https://[a-z0-9.\\-]+\\.travian\\.com$");

    @Test
    void generatesAModernTravianCatalogue() {
        CandidateUrlGenerator gen = new CandidateUrlGenerator();
        List<String> all = gen.generateAll();

        // 6 communities × (10 + 2 + 2 + 2 + 1 + 1 tx3) = 108 candidates today.
        // Range check leaves room for a future +/- community.
        assertTrue(all.size() >= 80 && all.size() <= 200,
                "expected ~108 candidates after the 2024 continent consolidation, got " + all.size());

        // Sanity-check well-known URLs from the modern scheme.
        assertTrue(all.contains("https://ts5.x1.arabics.travian.com"),
                "expected the Arabics x1 ts5 URL to be generated");
        assertTrue(all.contains("https://ts100.x10.arabics.travian.com"),
                "expected the Arabics x10 sprint URL to be generated");
        assertTrue(all.contains("https://ts5.x1.czsk.travian.com"),
                "expected the czsk x1 ts5 URL to be generated");
        assertTrue(all.contains("https://ts30.x3.europe.travian.com"),
                "expected the Europe x3 ts30 URL to be generated");
        assertTrue(all.contains("https://ts20.x2.international.travian.com"),
                "expected the International x2 ts20 URL to be generated");
        assertTrue(all.contains("https://tx3.europe.travian.com"),
                "expected the Tides of Conquest URL for Europe");

        // Speed/ts coupling — ts100 should ONLY exist as x10.
        for (String url : all) {
            if (url.contains("ts100.")) {
                assertTrue(url.contains(".x10."), "ts100 must be x10: " + url);
            }
            if (url.contains("ts50.") || url.contains("ts51.")) {
                assertTrue(url.contains(".x5."), "ts5x must be x5: " + url);
            }
            if (url.contains("ts30.") || url.contains("ts31.")) {
                assertTrue(url.contains(".x3."), "ts3x must be x3: " + url);
            }
            if (url.contains("ts20.") || url.contains("ts21.")) {
                assertTrue(url.contains(".x2."), "ts2x must be x2: " + url);
            }
        }

        // Every URL must match the canonical https://...travian.com shape.
        for (String url : all) {
            assertTrue(HTTPS_TRAVIAN.matcher(url).matches(),
                    "candidate does not match https://...travian.com shape: " + url);
            assertFalse(url.endsWith("/"),
                    "candidate must not have a trailing slash: " + url);
        }
    }

    @Test
    void candidatesAreUnique() {
        CandidateUrlGenerator gen = new CandidateUrlGenerator();
        List<String> all = gen.generateAll();
        assertTrue(new HashSet<>(all).size() == all.size(),
                "candidate URLs must be unique");
    }
}
