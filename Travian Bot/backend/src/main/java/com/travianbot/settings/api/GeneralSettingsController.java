package com.travianbot.settings.api;

import com.travianbot.settings.api.dto.GeneralSettingsRequest;
import com.travianbot.settings.api.dto.GeneralSettingsResponse;
import com.travianbot.settings.service.GeneralSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/general")
public class GeneralSettingsController {

    private final GeneralSettingsService service;

    public GeneralSettingsController(GeneralSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public GeneralSettingsResponse get() {
        return GeneralSettingsResponse.from(service.get());
    }

    @PutMapping
    public ResponseEntity<GeneralSettingsResponse> update(@Valid @RequestBody GeneralSettingsRequest req) {
        return ResponseEntity.ok(GeneralSettingsResponse.from(service.update(req)));
    }
}
