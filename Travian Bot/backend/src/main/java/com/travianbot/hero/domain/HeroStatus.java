package com.travianbot.hero.domain;

/**
 * High-level hero state as shown on the hero attribute page.
 * <p>
 * {@code HOME} and {@code MOVING} cover the common cases; {@code IN_ADVENTURE} is the
 * state while the hero is actually off on an adventure (distinct from a normal troop
 * movement); {@code DEAD} means he's been killed and needs reviving; {@code UNKNOWN}
 * is the parser's escape hatch when the skin shows a state we don't recognise yet.
 */
public enum HeroStatus {
    HOME,
    MOVING,
    IN_ADVENTURE,
    DEAD,
    REVIVING,
    UNKNOWN
}
