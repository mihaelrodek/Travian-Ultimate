package com.travianbot.farmlist.api;

import com.travianbot.farmlist.api.dto.FarmlistRequest;
import com.travianbot.farmlist.api.dto.FarmlistResponse;
import com.travianbot.farmlist.service.FarmlistSendService;
import com.travianbot.farmlist.service.FarmlistService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/farmlists")
public class FarmlistController {

    private final FarmlistService service;
    private final FarmlistSendService sendService;

    public FarmlistController(FarmlistService service, FarmlistSendService sendService) {
        this.service = service;
        this.sendService = sendService;
    }

    @GetMapping
    public List<FarmlistResponse> list(@RequestParam(required = false) Long accountId) {
        return service.listDto(accountId);
    }

    @GetMapping("/{id}")
    public FarmlistResponse get(@PathVariable Long id) {
        return service.getDto(id);
    }

    @PostMapping
    public ResponseEntity<FarmlistResponse> create(@Valid @RequestBody FarmlistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDto(request));
    }

    @PutMapping("/{id}")
    public FarmlistResponse update(@PathVariable Long id, @Valid @RequestBody FarmlistRequest request) {
        return service.updateDto(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fires the rally-point "send all" action for a single list right now.
     * The response carries how many targets were submitted so the UI can
     * render a concrete toast instead of a vague "ok".
     */
    @PostMapping("/{id}/send-all")
    public SendListResponse sendAll(@PathVariable Long id) {
        int count = sendService.sendList(id);
        return new SendListResponse(id, count);
    }

    public record SendListResponse(Long farmlistId, int targetsSent) {}
}
