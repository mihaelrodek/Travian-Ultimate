package com.travianbot.account.event;

/**
 * Published BEFORE an account is deleted, so per-account modules (villages, notes,
 * farmlists, tasks, …) can clean up their own rows first and avoid FK violations.
 * Listeners should run synchronously inside the same transaction as the deletion.
 */
public record AccountDeletingEvent(Long accountId) {
}
