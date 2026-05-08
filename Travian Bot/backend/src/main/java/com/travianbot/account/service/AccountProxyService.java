package com.travianbot.account.service;

import com.travianbot.account.api.dto.CreateAccountProxyRequest;
import com.travianbot.account.api.dto.UpdateAccountProxyRequest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountProxy;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountProxyRepository;
import com.travianbot.account.persistence.AccountRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for per-account proxies. Enforces the single-active invariant: when a
 * row is marked active — whether at create time or via update — any previously
 * active sibling is flipped off in the same transaction.
 * <p>
 * The browser session manager reads {@link #findActive(Long)} to decide what
 * proxy to pass to Playwright; that lookup is deliberately service-level
 * rather than exposing the repository, so future logic (health-check, auto
 * rotation) has a single place to live.
 */
@Service
@Transactional
public class AccountProxyService {

    private final AccountProxyRepository repository;
    private final AccountRepository accountRepository;

    public AccountProxyService(AccountProxyRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountProxy> list(Long accountId) {
        return repository.findByAccountIdOrderByCreatedAtAsc(accountId);
    }

    @Transactional(readOnly = true)
    public AccountProxy get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("AccountProxy " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<AccountProxy> findActive(Long accountId) {
        return repository.findByAccountIdAndActiveTrue(accountId);
    }

    public AccountProxy create(Long accountId, CreateAccountProxyRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        boolean makeActive = Boolean.TRUE.equals(request.active());
        if (makeActive) {
            deactivateCurrent(accountId);
        }
        AccountProxy proxy = new AccountProxy(
                account,
                trimToNull(request.label()),
                request.url().trim(),
                trimToNull(request.username()),
                // Empty password means "no credentials" — normalize to null so
                // AccountProxyResponse.passwordSet reads false.
                trimToNull(request.password()),
                makeActive
        );
        return repository.save(proxy);
    }

    public AccountProxy update(Long id, UpdateAccountProxyRequest request) {
        AccountProxy proxy = get(id);
        Long accountId = proxy.getAccount().getId();

        proxy.setLabel(trimToNull(request.label()));
        proxy.setUrl(request.url().trim());
        proxy.setUsername(trimToNull(request.username()));
        // Password semantics: null = "keep existing", empty = "clear it",
        // non-empty = "replace it". Lets the UI render a masked input and
        // only send the field when the user actually types a new value.
        if (request.password() != null) {
            proxy.setPassword(request.password().isBlank() ? null : request.password());
        }

        if (request.active() != null) {
            boolean wantActive = request.active();
            if (wantActive && !proxy.isActive()) {
                deactivateCurrent(accountId);
                proxy.setActive(true);
            } else if (!wantActive && proxy.isActive()) {
                proxy.setActive(false);
            }
        }
        return repository.save(proxy);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("AccountProxy " + id + " not found");
        }
        repository.deleteById(id);
    }

    /**
     * Clear the active flag on whichever sibling currently holds it. Safe to
     * call even when no row is active. Not exposed — callers go through
     * create/update and let the service enforce the invariant.
     */
    private void deactivateCurrent(Long accountId) {
        repository.findByAccountIdAndActiveTrue(accountId).ifPresent(existing -> {
            existing.setActive(false);
            repository.save(existing);
        });
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Cascade cleanup before the parent account row is deleted. MANDATORY
     * propagation matches the pattern used by the other per-account modules
     * (NoteService, FarmlistService, …) — we want to run inside the delete
     * transaction, not spawn a new one.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
