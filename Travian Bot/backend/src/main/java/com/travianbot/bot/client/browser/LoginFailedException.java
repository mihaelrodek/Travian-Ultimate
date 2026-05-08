package com.travianbot.bot.client.browser;

/**
 * Raised when a login attempt fails to reach an ingame page.
 * Caller is expected to translate this into a user-facing message.
 */
public class LoginFailedException extends RuntimeException {
    public LoginFailedException(String message) {
        super(message);
    }

    public LoginFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
