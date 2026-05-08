package com.travianbot.account.service;

import com.travianbot.account.api.dto.CreateAccountRequest;
import com.travianbot.account.api.dto.UpdateAccountRequest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AccountService {

    private final AccountRepository repository;
    private final AccountSettingsService settingsService;
    private final ApplicationEventPublisher events;

    public AccountService(
            AccountRepository repository,
            AccountSettingsService settingsService,
            ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.settingsService = settingsService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    public Account create(CreateAccountRequest request) {
        // @NotBlank on the DTO guarantees nickname is non-null and non-blank,
        // so a naked .trim() is safe. Account's constructor still enforces
        // non-blank server-side in case a future caller bypasses this path.
        Account account = new Account(
                normalize(request.serverUrl()),
                request.username().trim(),
                request.password(),
                request.nickname().trim()
        );
        Account saved = repository.save(account);
        // Every account has exactly one settings row. Created eagerly so the /settings
        // endpoint never has to deal with "missing-row" branching.
        settingsService.createDefaults(saved);
        return saved;
    }

    /**
     * Apply edits from the account-detail page: server URL, username, password
     * and nickname. Same normalization as {@link #create} — server URL trimmed
     * with trailing slash dropped, nickname trimmed (non-blank enforced by DTO).
     * <p>
     * We deliberately do NOT tear down any open Playwright session here — if
     * the user edited credentials the next login attempt will pick up the new
     * values, and if they only renamed the account there's nothing to disrupt.
     */
    public Account update(Long id, UpdateAccountRequest request) {
        Account account = get(id);
        account.updateCredentials(
                normalize(request.serverUrl()),
                request.username().trim(),
                request.password()
        );
        account.setNickname(request.nickname().trim());
        return repository.save(account);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new AccountNotFoundException(id);
        }
        // Settings row (and its active_window children via orphanRemoval) must go first
        // because they hold an FK to account.
        settingsService.deleteByAccountId(id);
        // Notify per-account modules (villages, notes, farmlists, tasks, …) so they
        // can drop their rows inside this transaction before the account row goes.
        events.publishEvent(new AccountDeletingEvent(id));
        repository.deleteById(id);
    }

    public void recordLoginAttempt(Long id, String status, String message) {
        Account account = get(id);
        account.recordLoginAttempt(status, message);
        repository.save(account);
    }

    private String normalize(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
