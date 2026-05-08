package com.travianbot.account.api.dto;

import com.travianbot.account.domain.AccountProxy;

import java.time.Instant;

/**
 * Wire shape for a configured proxy row. Never echoes the proxy password —
 * the UI flags whether one is stored via {@link #passwordSet} so the form can
 * render "••••" as a read-only hint, mirroring how {@code AccountSettingsResponse}
 * handles the legacy single-proxy credentials.
 */
public record AccountProxyResponse(
        Long id,
        String label,
        String url,
        String username,
        boolean passwordSet,
        boolean active,
        Instant createdAt
) {
    public static AccountProxyResponse from(AccountProxy p) {
        return new AccountProxyResponse(
                p.getId(),
                p.getLabel(),
                p.getUrl(),
                p.getUsername(),
                p.getPassword() != null && !p.getPassword().isBlank(),
                p.isActive(),
                p.getCreatedAt()
        );
    }
}
