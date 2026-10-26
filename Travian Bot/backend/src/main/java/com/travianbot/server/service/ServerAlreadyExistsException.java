package com.travianbot.server.service;

/**
 * Thrown by {@link ServerService#create} when a row with the same canonical
 * URL is already present. The URL is exposed so the controller / UI can
 * render a useful message ("ts1.travian.com is already tracked").
 */
public class ServerAlreadyExistsException extends RuntimeException {

    private final String url;

    public ServerAlreadyExistsException(String url) {
        super("Server already exists for url: " + url);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
