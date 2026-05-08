/**
 * Page objects — typed wrappers over parsed Travian pages.
 * <p>
 * A page object holds a raw HTML {@code org.jsoup.nodes.Document} and exposes typed getters
 * (e.g. {@code OverviewPage.resources()}, {@code OverviewPage.villages()}) by delegating to
 * the corresponding parser in {@link com.travianbot.bot.parser}.
 * <p>
 * Page objects are transport-agnostic — they work with HTML from either Playwright or HttpClient.
 */
package com.travianbot.bot.page;
