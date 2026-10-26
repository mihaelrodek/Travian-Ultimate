package com.travianbot.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for the static URL parsing helpers on
 * {@link ServerService}. No Spring, no DB.
 */
class ServerSpeedParserTest {

    @Test
    @DisplayName("ts5.x1.arabics.travian.com → speed 1, radius 200")
    void x1WorldParses() {
        Integer speed = ServerService.parseSpeedFromUrl("https://ts5.x1.arabics.travian.com");
        assertEquals(1, speed);
        assertEquals(200, ServerService.defaultRadiusForSpeed(speed));
    }

    @Test
    @DisplayName("ts100.x10.arabics.travian.com → speed 10, radius 100")
    void x10WorldParses() {
        Integer speed = ServerService.parseSpeedFromUrl("https://ts100.x10.arabics.travian.com");
        assertEquals(10, speed);
        assertEquals(100, ServerService.defaultRadiusForSpeed(speed));
    }

    @Test
    @DisplayName("ts20.x2.europe.travian.com → speed 2, radius 200")
    void x2WorldParses() {
        Integer speed = ServerService.parseSpeedFromUrl("https://ts20.x2.europe.travian.com");
        assertEquals(2, speed);
        assertEquals(200, ServerService.defaultRadiusForSpeed(speed));
    }

    @Test
    @DisplayName("URL without x-segment yields null (TOC hosts, legacy)")
    void nonMatchingUrlReturnsNull() {
        assertNull(ServerService.parseSpeedFromUrl("https://tx3.czsk.travian.com"));
        assertNull(ServerService.parseSpeedFromUrl("https://ts1.kingdoms.com"));
        assertNull(ServerService.parseSpeedFromUrl(null));
        assertNull(ServerService.parseSpeedFromUrl(""));
        assertNull(ServerService.parseSpeedFromUrl("   "));
    }

    @Test
    @DisplayName("Speed clamped to sane range (1..10)")
    void garbageSpeedRejected() {
        // x99 was a hypothetical — Travian doesn't run >x10. The parser
        // rejects it so a typo doesn't end up persisted as the world speed.
        assertNull(ServerService.parseSpeedFromUrl("https://ts1.x99.foo.travian.com"));
    }

    @Test
    @DisplayName("defaultRadiusForSpeed(null) → null (caller decides fallback)")
    void radiusForUnknownSpeed() {
        assertNull(ServerService.defaultRadiusForSpeed(null));
    }

    @Test
    @DisplayName("Speed parsing is case-insensitive (X1 / X10)")
    void caseInsensitiveSpeedSegment() {
        assertEquals(1, ServerService.parseSpeedFromUrl("https://ts5.X1.arabics.travian.com"));
        assertEquals(10, ServerService.parseSpeedFromUrl("https://ts100.X10.arabics.travian.com"));
    }
}
