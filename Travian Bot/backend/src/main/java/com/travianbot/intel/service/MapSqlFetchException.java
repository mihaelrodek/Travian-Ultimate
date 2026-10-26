package com.travianbot.intel.service;

/**
 * Thrown when fetching the daily {@code map.sql} dump fails — non-200, timeout,
 * or a transport-level IOError. The server URL is captured so log lines and the
 * bot-event row both make sense without the caller having to wrap the message.
 */
public class MapSqlFetchException extends RuntimeException {

    private final String serverUrl;

    public MapSqlFetchException(String serverUrl, String message) {
        super("map.sql fetch failed for " + serverUrl + ": " + message);
        this.serverUrl = serverUrl;
    }

    public MapSqlFetchException(String serverUrl, String message, Throwable cause) {
        super("map.sql fetch failed for " + serverUrl + ": " + message, cause);
        this.serverUrl = serverUrl;
    }

    public String getServerUrl() {
        return serverUrl;
    }
}
