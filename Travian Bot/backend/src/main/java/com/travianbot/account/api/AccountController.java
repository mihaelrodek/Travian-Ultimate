package com.travianbot.account.api;

import com.travianbot.account.api.dto.AccountCredentialsResponse;
import com.travianbot.account.api.dto.AccountResponse;
import com.travianbot.account.api.dto.CreateAccountRequest;
import com.travianbot.account.api.dto.LoginResultResponse;
import com.travianbot.account.api.dto.UpdateAccountRequest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.service.AccountService;
import com.travianbot.account.service.LoginService;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.FarmlistScrapingService;
import com.travianbot.bot.scraper.ScrapingService;
import com.travianbot.farmlist.service.FarmlistSendService;
import com.travianbot.village.api.dto.VillageResponse;
import com.travianbot.village.service.VillageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LoginService loginService;
    private final ScrapingService scrapingService;
    private final VillageService villageService;
    private final FarmlistScrapingService farmlistScrapingService;
    private final FarmlistSendService farmlistSendService;
    private final BrowserSessionManager sessionManager;

    public AccountController(
            AccountService accountService,
            LoginService loginService,
            ScrapingService scrapingService,
            VillageService villageService,
            FarmlistScrapingService farmlistScrapingService,
            FarmlistSendService farmlistSendService,
            BrowserSessionManager sessionManager
    ) {
        this.accountService = accountService;
        this.loginService = loginService;
        this.scrapingService = scrapingService;
        this.villageService = villageService;
        this.farmlistScrapingService = farmlistScrapingService;
        this.farmlistSendService = farmlistSendService;
        this.sessionManager = sessionManager;
    }

    /**
     * True only if a Playwright session is currently open for this account AND
     * its {@link com.travianbot.bot.client.TravianSession} has recorded a
     * successful ingame landing. An account whose saved {@code lastLoginStatus}
     * is SUCCESS but whose browser has since been torn down (app restart, user
     * called Shut down) reads as {@code false} here — that's the whole point
     * of the red-dot signal.
     */
    private boolean isLiveLoggedIn(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null) return false;
        try {
            return client.session() != null && client.session().isLoggedIn();
        } catch (Exception e) {
            return false;
        }
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.from(account, isLiveLoggedIn(account.getId()));
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.list().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return toResponse(accountService.get(id));
    }

    /**
     * Returns the plaintext Travian password for a single account. Deliberately
     * separate from {@link #get} / {@link #list} so the secret is only fetched
     * on an explicit user action (reveal or edit) instead of on every poll. See
     * {@link AccountCredentialsResponse} for the plaintext caveat.
     */
    @GetMapping("/{id}/credentials")
    public AccountCredentialsResponse credentials(@PathVariable Long id) {
        return AccountCredentialsResponse.from(accountService.get(id));
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse body = toResponse(accountService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Update credentials (server URL, username, password) and/or the nickname
     * in one round-trip from the account detail page's inline Edit panel. All
     * four fields are required — the frontend prefills them with current
     * values so "edit nickname only" still sends a complete body.
     */
    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAccountRequest request) {
        return toResponse(accountService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/login")
    public LoginResultResponse login(@PathVariable Long id) {
        return loginService.login(id);
    }

    /**
     * Kills the Playwright browser for this account WITHOUT clicking Travian's
     * logout link. The game server still sees the last session as valid until
     * its own cookie timeout — this is the "close the laptop" semantic.
     */
    @PostMapping("/{id}/shutdown")
    public LoginResultResponse shutdown(@PathVariable Long id) {
        return loginService.shutdown(id);
    }

    /**
     * Clicks the ingame logout link, which ends the session on the server side
     * as well. Use this when you want Travian's {@code last login} timestamp to
     * advance, e.g. before deleting an account or handing it off to a sitter.
     * Still tears down the Playwright session afterwards.
     */
    @PostMapping("/{id}/in-game-logout")
    public LoginResultResponse inGameLogout(@PathVariable Long id) {
        return loginService.inGameLogout(id);
    }

    /**
     * Shutdown + login in one call. Useful when the browser is in a wedged
     * state (stale page, captcha stuck) and the user wants a clean slate
     * without manually toggling the two actions.
     */
    @PostMapping("/{id}/restart")
    public LoginResultResponse restart(@PathVariable Long id) {
        return loginService.restart(id);
    }

    /**
     * Scrapes whichever village is currently selected in the (logged-in) browser.
     * Use this after a fresh login to discover the starting village without needing
     * the user to tell us which one it is.
     */
    @PostMapping("/{id}/scrape-current-village")
    public VillageResponse scrapeCurrentVillage(@PathVariable Long id) {
        var village = scrapingService.scrapeCurrentVillage(id);
        return villageService.getDto(village.getId());
    }

    /**
     * Re-reads the rally-point "Farm lists" tab and reconciles the local
     * farmlist/target rows against whatever the server says. Returns the
     * number of farmlists the scraper saw.
     */
    @PostMapping("/{id}/scrape-farmlists")
    public ScrapeFarmlistsResponse scrapeFarmlists(@PathVariable Long id) {
        int count = farmlistScrapingService.scrapeFarmlists(id);
        return new ScrapeFarmlistsResponse(count);
    }

    /**
     * Refreshes every village on the account. Navigates dorf1/dorf2 for each
     * one sequentially — slow, but the button lives under "reload all" so
     * that's what the user asked for. Requires an open, logged-in browser
     * session (409 otherwise).
     */
    @PostMapping("/{id}/scrape-all-villages")
    public ScrapeAllVillagesResponse scrapeAllVillages(@PathVariable Long id) {
        int count = scrapingService.scrapeAllVillages(id);
        return new ScrapeAllVillagesResponse(count);
    }

    /**
     * Triggers send-all on every enabled farmlist belonging to the account.
     * Used by the Accounts page "Send all lists" button.
     */
    @PostMapping("/{id}/send-all-farmlists")
    public SendAllFarmlistsResponse sendAllFarmlists(@PathVariable Long id) {
        int count = farmlistSendService.sendAllForAccount(id);
        return new SendAllFarmlistsResponse(count);
    }

    public record ScrapeFarmlistsResponse(int farmlistCount) {}
    public record SendAllFarmlistsResponse(int listsSent) {}
    public record ScrapeAllVillagesResponse(int villagesScraped) {}
}
