/**
 * HTML parsers — pure functions that extract typed data from a Jsoup {@code Document}.
 * <p>
 * No network I/O, no DB access, no Spring. Unit-testable against saved HTML fixtures under
 * {@code src/test/resources/html/}. Logic is ported from TravBotSharp's {@code MainCore/Parsers/}
 * (HtmlAgilityPack → Jsoup; static class methods → static methods).
 */
package com.travianbot.bot.parser;
