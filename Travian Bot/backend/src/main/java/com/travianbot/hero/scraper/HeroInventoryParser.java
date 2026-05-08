package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.HeroItemCategory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the hero/inventory page.
 * <p>
 * Travian renders the hero's equipment as a grid of slots inside {@code .equipment}
 * (or {@code #equipment}) and the inventory grid inside {@code .inventoryList}
 * (or {@code #itemsToSale}). Each item has:
 * <ul>
 *   <li>an {@code item-<gid>} class from which we parse the Travian item id</li>
 *   <li>an {@code img} with a {@code tooltip}-able title = item display name</li>
 *   <li>a {@code .amount} for stacks (bandages etc.)</li>
 * </ul>
 * We derive category from the CSS class's slot hint (e.g. {@code itemCategory-helmet})
 * with a keyword-based fallback so the UI still lands in the right bucket when the
 * skin renames classes.
 */
public final class HeroInventoryParser {

    private static final Pattern ITEM_ID_CLASS = Pattern.compile("item-(\\d+)");

    private HeroInventoryParser() {
    }

    public static List<ParsedHeroItem> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<ParsedHeroItem> out = new ArrayList<>();

        // Equipped gear lives in the hero's equipment panel. Each slot has a known
        // CSS class (slot-helmet, slot-weapon, ...) which we use both to extract the
        // category AND to number the slot so the UI can render them in a stable order.
        Elements equipSlots = doc.select(
                ".equipment .slot, .equipment .item, #equipment .slot, "
                        + ".heroEquipment .slot"
        );
        int slotIndex = 0;
        for (Element slot : equipSlots) {
            Element itemEl = slot.selectFirst(".item, .hasItem, .occupied");
            if (itemEl == null) itemEl = slot; // some skins flatten it
            ParsedHeroItem parsed = parseItem(itemEl, true, slotIndex++, inferCategoryFromSlot(slot));
            if (parsed != null) out.add(parsed);
        }

        // Inventory (non-equipped) items live in a flat grid.
        Elements inv = doc.select(
                ".inventoryList .item, .inventory .item, #inventory .item, "
                        + "#itemsToSale .item, .heroInventory .item"
        );
        for (Element itemEl : inv) {
            ParsedHeroItem parsed = parseItem(itemEl, false, null, inferCategoryFromClasses(itemEl));
            if (parsed != null) out.add(parsed);
        }

        return out;
    }

    /**
     * Pulls the item id, display name, stack amount and image URL out of an
     * {@code .item}-like element. Returns {@code null} if the element is an
     * empty slot (no travianItemId).
     */
    private static ParsedHeroItem parseItem(Element el, boolean equipped, Integer slot,
                                             HeroItemCategory category) {
        if (el == null) return null;
        Long travianId = parseTravianItemId(el);
        Element img = el.selectFirst("img");
        String name = null;
        String imageUrl = null;
        if (img != null) {
            name = firstNonBlank(img.attr("alt"), img.attr("title"));
            String src = img.attr("src");
            imageUrl = src.isBlank() ? null : src;
        }
        if (name == null) {
            String txt = el.text();
            if (!txt.isBlank()) name = txt.trim();
        }
        if (travianId == null && name == null) return null; // nothing useful

        // Amount: explicit .amount or data-amount attribute; default 1 for gear.
        int amount = 1;
        Element amtEl = el.selectFirst(".amount, .count, .stack");
        if (amtEl != null) {
            Integer parsed = parseInt(amtEl.text());
            if (parsed != null && parsed > 0) amount = parsed;
        } else {
            String da = el.attr("data-amount");
            if (!da.isBlank()) {
                Integer parsed = parseInt(da);
                if (parsed != null && parsed > 0) amount = parsed;
            }
        }

        return new ParsedHeroItem(travianId, name, category, amount, equipped, slot, imageUrl);
    }

    /**
     * Travian tags each item with {@code class="item item-<tgid> ..."}. Extract the tgid.
     */
    private static Long parseTravianItemId(Element el) {
        String classes = el.className() + " " + el.select("*").eachAttr("class");
        Matcher m = ITEM_ID_CLASS.matcher(classes);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static HeroItemCategory inferCategoryFromSlot(Element slot) {
        String cls = slot.className().toLowerCase(Locale.ROOT);
        if (cls.contains("helmet") || cls.contains("head")) return HeroItemCategory.HELMET;
        if (cls.contains("weapon") || cls.contains("right")) return HeroItemCategory.WEAPON;
        if (cls.contains("armor") || cls.contains("armour") || cls.contains("body")) return HeroItemCategory.ARMOR;
        if (cls.contains("left") || cls.contains("shield") || cls.contains("bag") || cls.contains("map")) return HeroItemCategory.LEFT_HAND;
        if (cls.contains("horse") || cls.contains("mount")) return HeroItemCategory.HORSE;
        if (cls.contains("boot")) return HeroItemCategory.BOOTS;
        return HeroItemCategory.OTHER;
    }

    private static HeroItemCategory inferCategoryFromClasses(Element el) {
        String haystack = (el.className() + " "
                + el.select("*").eachAttr("class")
                + " " + el.text()).toLowerCase(Locale.ROOT);
        if (haystack.contains("bandage") || haystack.contains("verband")) {
            if (haystack.contains("small") || haystack.contains("klein")) return HeroItemCategory.SMALL_BANDAGE;
            return HeroItemCategory.BANDAGE;
        }
        if (haystack.contains("ointment") || haystack.contains("salbe")) return HeroItemCategory.OINTMENT;
        if (haystack.contains("scroll") || haystack.contains("schriftrolle")) return HeroItemCategory.SCROLL;
        if (haystack.contains("bucket") || haystack.contains("eimer")) return HeroItemCategory.BUCKET;
        if (haystack.contains("cage") || haystack.contains("käfig")) return HeroItemCategory.CAGE;
        if (haystack.contains("artwork") || haystack.contains("kunstwerk")) return HeroItemCategory.ARTWORK;
        if (haystack.contains("book") || haystack.contains("buch")) return HeroItemCategory.BOOK_OF_WISDOM;
        if (haystack.contains("tablet") || haystack.contains("tafel")) return HeroItemCategory.TABLET_OF_LAW;
        if (haystack.contains("helmet") || haystack.contains("helm")) return HeroItemCategory.HELMET;
        if (haystack.contains("weapon") || haystack.contains("sword") || haystack.contains("schwert") || haystack.contains("axe")) return HeroItemCategory.WEAPON;
        if (haystack.contains("armor") || haystack.contains("rüstung") || haystack.contains("ruestung")) return HeroItemCategory.ARMOR;
        if (haystack.contains("shield") || haystack.contains("schild") || haystack.contains("bag") || haystack.contains("beutel") || haystack.contains("map") || haystack.contains("karte")) return HeroItemCategory.LEFT_HAND;
        if (haystack.contains("horse") || haystack.contains("pferd")) return HeroItemCategory.HORSE;
        if (haystack.contains("boot") || haystack.contains("stiefel")) return HeroItemCategory.BOOTS;
        if (haystack.contains("exp") || haystack.contains("erfahrung")) return HeroItemCategory.EXP_BONUS;
        if (haystack.contains("crop") || haystack.contains("getreide")) return HeroItemCategory.CROP_BONUS;
        if (haystack.contains("resource") || haystack.contains("rohstoff")) return HeroItemCategory.RESOURCE_BONUS;
        return HeroItemCategory.OTHER;
    }

    private static Integer parseInt(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
