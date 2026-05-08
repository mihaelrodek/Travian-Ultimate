package com.travianbot.account.api;

import com.travianbot.account.api.dto.AccountProxyResponse;
import com.travianbot.account.api.dto.CreateAccountProxyRequest;
import com.travianbot.account.api.dto.UpdateAccountProxyRequest;
import com.travianbot.account.service.AccountProxyService;
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

/**
 * REST surface for per-account proxy rotation. Nested under the account URL
 * (no cross-account listing) so the UI never has to scope proxies itself — if
 * you're on account 42's detail page you GET
 * {@code /api/accounts/42/proxies} and trust that every row belongs to that
 * account.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/proxies")
public class AccountProxyController {

    private final AccountProxyService service;

    public AccountProxyController(AccountProxyService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccountProxyResponse> list(@PathVariable Long accountId) {
        return service.list(accountId).stream()
                .map(AccountProxyResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<AccountProxyResponse> create(
            @PathVariable Long accountId,
            @Valid @RequestBody CreateAccountProxyRequest request
    ) {
        AccountProxyResponse body = AccountProxyResponse.from(service.create(accountId, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public AccountProxyResponse update(
            @PathVariable Long accountId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateAccountProxyRequest request
    ) {
        return AccountProxyResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long accountId,
            @PathVariable Long id
    ) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
