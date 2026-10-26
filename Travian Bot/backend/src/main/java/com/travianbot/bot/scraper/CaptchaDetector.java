package com.travianbot.bot.scraper;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic "is this page a captcha/anti-bot challenge instead of the game?"
 * check, run against raw page HTML before any parser touches it.
 * <p>
 * Markers are deliberately narrow — vendor-specific identifiers (GeeTest is
 * what Travian Legends actually serves; reCAPTCHA/hCaptcha/Turnstile and the
 * Cloudflare interstitial covered for good measure) plus an id/class named
 * exactly {@code captcha}. The bare word "captcha" alone is NOT a marker: it
 * could legitimately appear in message text, a report, or a village named by
 * a joker.
 */
final class CaptchaDetector {

    /** Substring markers, matched against lowercased HTML. */
    private static final List<String> MARKERS = List.of(
            // GeeTest — the vendor Travian Legends uses.
            "initgeetest",
            "geetest_captcha",
            "geetest.com",
            // Google reCAPTCHA / hCaptcha / Cloudflare Turnstile widgets.
            "g-recaptcha",
            "grecaptcha.execute",
            "h-captcha",
            "hcaptcha.com",
            "cf-turnstile",
            // Cloudflare interstitial ("Just a moment…") internals.
            "challenge-platform",
            "cf_chl_",
            // Generic but precise: an element literally id'd/classed "captcha".
            "id=\"captcha\"",
            "class=\"captcha\""
    );

    private CaptchaDetector() {
    }

    static boolean looksLikeCaptcha(String html) {
        if (html == null || html.isEmpty()) return false;
        String lower = html.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}
