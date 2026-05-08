package com.travianbot.account.service;

import com.travianbot.account.api.dto.AccountSettingsRequest;
import com.travianbot.account.api.dto.AccountSettingsResponse;
import com.travianbot.account.api.dto.ActiveWindowDto;
import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.ActiveWindow;
import com.travianbot.account.domain.Tribe;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.persistence.AccountSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class AccountSettingsService {

    private final AccountSettingsRepository repository;
    private final AccountRepository accountRepository;

    public AccountSettingsService(AccountSettingsRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    /**
     * Creates a default-valued settings row for a newly created account.
     * Called from {@link AccountService#create} so every account always has a settings row.
     */
    public AccountSettings createDefaults(Account account) {
        return repository.save(new AccountSettings(account));
    }

    /**
     * Returns settings, creating defaults on the fly if the account pre-dates this feature.
     * Keeps the /settings endpoint infallible as long as the account exists.
     */
    public AccountSettings getForAccount(Long accountId) {
        return repository.findByAccountId(accountId)
                .orElseGet(() -> {
                    Account account = accountRepository.findById(accountId)
                            .orElseThrow(() -> new AccountNotFoundException(accountId));
                    return createDefaults(account);
                });
    }

    /**
     * Returns the DTO form of settings for an account. IMPORTANT: with
     * {@code spring.jpa.open-in-view=false} the controller layer can't walk
     * lazy relationships ({@code account}, {@code activeWindows}), so we
     * must materialize the DTO inside this {@code @Transactional} method.
     * If the caller held the entity until after the tx closed, the mapping
     * would throw LazyInitializationException → 500 "Unexpected error".
     */
    public AccountSettingsResponse getForAccountDto(Long accountId) {
        return AccountSettingsResponse.from(getForAccount(accountId));
    }

    /**
     * Full replace. Validates the NPC ratio sum and the active-window bounds before persisting.
     */
    /**
     * Removes the settings row for an account (if any). Cascades to its active windows via
     * the @OneToMany orphanRemoval on {@link AccountSettings#replaceActiveWindows}.
     * Safe to call before deleting the parent Account — must be called to avoid an FK error.
     */
    public void deleteByAccountId(Long accountId) {
        repository.findByAccountId(accountId).ifPresent(repository::delete);
    }

    /** Transactional wrapper that also maps to DTO — same reasoning as {@link #getForAccountDto}. */
    public AccountSettingsResponse updateDto(Long accountId, AccountSettingsRequest req) {
        return AccountSettingsResponse.from(update(accountId, req));
    }

    public AccountSettings update(Long accountId, AccountSettingsRequest req) {
        AccountSettings s = getForAccount(accountId);

        validateWindows(req.activeWindows());

        // Tribe is auto-detected on login — we still accept whatever the client
        // sent so the round-trip works, but we only overwrite if the client
        // actually supplied a value (not null).
        if (req.tribe() != null) {
            s.setTribe(req.tribe());
        }

        s.setHeroAutoLevel(req.heroAutoLevel());
        s.setHeroHealThresholdPct(req.heroHealThresholdPct());
        s.setHeroAutoAdventure(req.heroAutoAdventure());
        s.setHeroMinHealthForAdventure(req.heroMinHealthForAdventure());

        s.setAlertOnAttack(req.alertOnAttack());
        s.setAlertIncludeTroopCounts(req.alertIncludeTroopCounts());
        s.setAlertChannels(joinChannels(req.alertChannels()));

        s.setProxyUrl(blankToNull(req.proxyUrl()));
        s.setProxyUsername(blankToNull(req.proxyUsername()));
        // Null password means "don't touch"; empty string means "clear"; anything else replaces.
        if (req.proxyPassword() != null) {
            s.setProxyPassword(req.proxyPassword().isEmpty() ? null : req.proxyPassword());
        }

        List<ActiveWindow> windows = new ArrayList<>();
        for (ActiveWindowDto dto : req.activeWindows()) {
            windows.add(new ActiveWindow(s, dto.dayOfWeek(), dto.startMinute(), dto.endMinute()));
        }
        s.replaceActiveWindows(windows);

        return repository.save(s);
    }

    /**
     * Auto-populates the tribe field from a scraped profile page. Called from
     * {@link LoginService}'s post-login hook. No-op if the tribe is already set
     * so we don't thrash the row on every login. Returns the final tribe value
     * for logging convenience.
     */
    public Tribe autoDetectTribe(Long accountId, Tribe detected) {
        if (detected == null) return null;
        AccountSettings s = getForAccount(accountId);
        if (s.getTribe() != null) return s.getTribe();
        s.setTribe(detected);
        repository.save(s);
        return detected;
    }

    private void validateWindows(List<ActiveWindowDto> windows) {
        if (windows == null) return;
        for (ActiveWindowDto w : windows) {
            if (w.startMinute() >= w.endMinute()) {
                throw new IllegalArgumentException(
                        "Active window for " + w.dayOfWeek() + " has start >= end (" + w.startMinute() + " >= " + w.endMinute() + ")");
            }
        }
    }

    private static String joinChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) return null;
        return String.join(",", channels);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
