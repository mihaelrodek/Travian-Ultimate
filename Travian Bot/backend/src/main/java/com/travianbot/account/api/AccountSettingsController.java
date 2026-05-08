package com.travianbot.account.api;

import com.travianbot.account.api.dto.AccountSettingsRequest;
import com.travianbot.account.api.dto.AccountSettingsResponse;
import com.travianbot.account.service.AccountSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/settings")
public class AccountSettingsController {

    private final AccountSettingsService service;

    public AccountSettingsController(AccountSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public AccountSettingsResponse get(@PathVariable Long accountId) {
        // Must use the DTO-returning service method — open-in-view is off, so lazy
        // relationships (account, activeWindows) can't be walked after tx closes.
        return service.getForAccountDto(accountId);
    }

    @PutMapping
    public ResponseEntity<AccountSettingsResponse> update(
            @PathVariable Long accountId,
            @Valid @RequestBody AccountSettingsRequest req
    ) {
        return ResponseEntity.ok(service.updateDto(accountId, req));
    }
}
