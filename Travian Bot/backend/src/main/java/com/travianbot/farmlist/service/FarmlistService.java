package com.travianbot.farmlist.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.farmlist.api.dto.FarmlistRequest;
import com.travianbot.farmlist.api.dto.FarmlistResponse;
import com.travianbot.farmlist.api.dto.FarmlistTargetDto;
import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.persistence.FarmlistRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class FarmlistService {

    private final FarmlistRepository repository;
    private final AccountRepository accountRepository;

    public FarmlistService(FarmlistRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<Farmlist> list(Long accountId) {
        return (accountId == null)
                ? repository.findAllByOrderByCreatedAtAsc()
                : repository.findAllByAccountIdOrderByCreatedAtAsc(accountId);
    }

    /**
     * List + map to DTO inside the same transaction — open-in-view is off so
     * the lazy {@code targets} collection must be walked before the controller
     * layer.
     */
    @Transactional(readOnly = true)
    public List<FarmlistResponse> listDto(Long accountId) {
        return list(accountId).stream().map(FarmlistResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Farmlist get(Long id) {
        return repository.findById(id).orElseThrow(() -> new FarmlistNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public FarmlistResponse getDto(Long id) {
        return FarmlistResponse.from(get(id));
    }

    public FarmlistResponse createDto(FarmlistRequest req) {
        return FarmlistResponse.from(create(req));
    }

    public FarmlistResponse updateDto(Long id, FarmlistRequest req) {
        return FarmlistResponse.from(update(id, req));
    }

    public Farmlist create(FarmlistRequest req) {
        if (req.accountId() == null) {
            throw new IllegalArgumentException("accountId is required to create a farmlist");
        }
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException(req.accountId()));
        Farmlist fl = new Farmlist(account, req.name().trim());
        fl.update(req.name().trim(), req.description(), req.enabled());
        fl.updatePolicy(req.type(), req.sendIntervalMinutes(), normalizeTag(req.groupTag()));
        if (req.targets() != null) replaceTargets(fl, req.targets());
        return repository.save(fl);
    }

    public Farmlist update(Long id, FarmlistRequest req) {
        Farmlist fl = get(id);
        fl.update(req.name().trim(), req.description(), req.enabled());
        fl.updatePolicy(req.type(), req.sendIntervalMinutes(), normalizeTag(req.groupTag()));
        if (req.targets() != null) replaceTargets(fl, req.targets());
        return repository.save(fl);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) throw new FarmlistNotFoundException(id);
        repository.deleteById(id);
    }

    private void replaceTargets(Farmlist fl, List<FarmlistTargetDto> incoming) {
        List<FarmlistTarget> existing = fl.getTargets();
        var byId = existing.stream()
                .filter(t -> t.getId() != null)
                .collect(Collectors.toMap(FarmlistTarget::getId, t -> t));

        List<FarmlistTarget> next = new ArrayList<>();
        for (FarmlistTargetDto dto : incoming) {
            FarmlistTarget t;
            if (dto.id() != null && byId.containsKey(dto.id())) {
                t = byId.get(dto.id());
                t.update(dto.name(), dto.coordinateX(), dto.coordinateY(), dto.troopsJson(), dto.enabled());
            } else {
                t = new FarmlistTarget(fl, dto.name(), dto.coordinateX(), dto.coordinateY(), dto.troopsJson());
                if (!dto.enabled()) t.update(dto.name(), dto.coordinateX(), dto.coordinateY(), dto.troopsJson(), false);
            }
            if (dto.travianTargetId() != null) t.setTravianTargetId(dto.travianTargetId());
            next.add(t);
        }

        existing.clear();
        existing.addAll(next);
    }

    private static String normalizeTag(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
