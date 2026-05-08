package com.travianbot.template.domain;

/**
 * Rough classification so the user can filter a long list of templates into
 * "what I'd pick for an offensive capital" vs "what I'd put in a defensive
 * support village." Deliberately open-ended — the bot doesn't branch on this,
 * it's purely a user-facing label.
 */
public enum TemplateCategory {
    OFFENSE,
    DEFENSE,
    SUPPORT
}
