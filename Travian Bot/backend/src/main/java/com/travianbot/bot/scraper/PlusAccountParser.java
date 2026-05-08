package com.travianbot.bot.scraper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Detects whether the logged-in account currently has a Travian Plus subscription.
 * The Plus icon + countdown sits in the top premium strip that dorf1 / dorf2 render,
 * and there's also a CSS class that T4.6 toggles on {@code <body>} when Plus is on.
 * <p>
 * Plus detection is inherently fragile — the exact markup has changed between T4.x
 * point releases — so we cast a wide net (multiple selectors + a text search for
 * "Travian Plus" in the premium bar). When <em>any</em> of the signals matches we
 * treat the account as plus; otherwise false. That avoids the "false positive"
 * failure mode (awarding an extra build slot the game won't actually permit) at
 * the cost of the "false negative" mode (under-using capacity when the markup
 * has drifted), which is strictly safer.
 */
public final class PlusAccountParser {

    private PlusAccountParser() {
    }

    /**
     * Parse a dorf1/dorf2/anywhere HTML snippet for Plus status.
     * @return true if we see a confident "Plus active" signal, false otherwise.
     */
    public static boolean isPlusActive(Document doc) {
        if (doc == null) return false;

        // Signal 1 — T4.6 toggles `plus` or `travianPlus` as a body class when
        // the subscription is active. Cheap test, try first.
        Element body = doc.body();
        if (body != null) {
            String cls = body.attr("class");
            if (cls != null && !cls.isBlank()) {
                String lower = cls.toLowerCase();
                if (lower.contains("travianplus") || containsClassToken(lower, "plus")) {
                    // Careful: `.plus` can appear as a substring of `plusMenu` etc.
                    // containsClassToken checks token boundaries to avoid that.
                    return true;
                }
            }
        }

        // Signal 2 — the premium navigation strip has a badge / countdown when
        // plus is active. Look for the canonical wrappers T4.6 ships with.
        String[] activeBadgeSelectors = {
                "#plus.active",                // variants with .active / .on suffix
                "#plus.on",
                ".plusContainer.active",
                ".plusFeature.active",
                ".premiumFeature.plus.active",
                ".iconPlus.active",
                ".plusAccountIcon",
                ".plusIcon"
        };
        for (String sel : activeBadgeSelectors) {
            if (doc.selectFirst(sel) != null) return true;
        }

        // Signal 3 — a countdown timer under the plus icon implies it's running
        // (vs. the "extend your Plus" CTA that shows when it's off). Look for a
        // `.timer` child of any wrapper whose class mentions plus.
        Elements plusWrappers = doc.select("#plus, .plusContainer, .plusFeature");
        for (Element w : plusWrappers) {
            Element timer = w.selectFirst(".timer, .countdown");
            if (timer != null && !timer.text().isBlank()) return true;
        }

        // Signal 4 — tooltip text. When Plus is OFF the icon's title usually reads
        // "Activate Travian Plus" or similar; when ON it reads "Travian Plus" +
        // countdown. This is the weakest signal but saves us when class names
        // drift.
        for (Element w : plusWrappers) {
            String title = w.attr("title");
            if (title == null) continue;
            String lower = title.toLowerCase();
            if (lower.contains("travian plus") && !lower.contains("activate")
                    && !lower.contains("extend") && !lower.contains("buy")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Matches {@code token} as a whole CSS class, not a substring — {@code plusMenu}
     * must NOT count as "plus".
     */
    private static boolean containsClassToken(String classAttr, String token) {
        for (String c : classAttr.split("\\s+")) {
            if (c.equals(token)) return true;
        }
        return false;
    }
}
