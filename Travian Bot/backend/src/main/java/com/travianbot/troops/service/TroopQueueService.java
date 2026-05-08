package com.travianbot.troops.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.Tribe;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.troops.api.dto.TroopQueueItemDto;
import com.travianbot.troops.domain.TroopBuilding;
import com.travianbot.troops.domain.TroopCatalog;
import com.travianbot.troops.domain.TroopCatalog.TroopInfo;
import com.travianbot.troops.domain.TroopQueueItem;
import com.travianbot.troops.persistence.TroopQueueItemRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.service.VillageNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read and edit the per-village troop training queue.
 * <p>
 * Mirrors the {@code replaceQueue} pattern from {@code VillageService}: the
 * frontend hands us the full ordered list, and we reconcile — existing rows
 * keep their status/createdAt, missing ones are removed, new ones are created.
 * <p>
 * Catalog validation is done on write: if the incoming {@code troopCode} does
 * not exist in the account's tribe catalog, we reject rather than persist a
 * row the scheduler later won't know what to do with. The {@code building}
 * field on the DTO is re-derived from the catalog server-side — the client's
 * value is accepted as a hint but ignored if the catalog says otherwise.
 */
@Service
@Transactional
public class TroopQueueService {

    private static final Logger log = LoggerFactory.getLogger(TroopQueueService.class);

    private final TroopQueueItemRepository queueRepository;
    private final VillageRepository villageRepository;
    private final AccountSettingsRepository accountSettingsRepository;

    public TroopQueueService(
            TroopQueueItemRepository queueRepository,
            VillageRepository villageRepository,
            AccountSettingsRepository accountSettingsRepository
    ) {
        this.queueRepository = queueRepository;
        this.villageRepository = villageRepository;
        this.accountSettingsRepository = accountSettingsRepository;
    }

    @Transactional(readOnly = true)
    public List<TroopQueueItemDto> listForVillage(Long villageId) {
        if (!villageRepository.existsById(villageId)) {
            throw new VillageNotFoundException(villageId);
        }
        return queueRepository.findAllByVillageIdOrderByOrderIndexAscIdAsc(villageId)
                .stream().map(TroopQueueItemDto::from).toList();
    }

    public List<TroopQueueItemDto> replaceQueue(Long villageId, List<TroopQueueItemDto> incoming) {
        Village village = villageRepository.findById(villageId)
                .orElseThrow(() -> new VillageNotFoundException(villageId));

        Tribe tribe = resolveTribe(village.getAccount());

        // Index existing rows by id so we can preserve status/createdAt where
        // the client sent back an id it already knew about.
        List<TroopQueueItem> existing =
                queueRepository.findAllByVillageIdOrderByOrderIndexAscIdAsc(villageId);
        var byId = existing.stream()
                .filter(it -> it.getId() != null)
                .collect(java.util.stream.Collectors.toMap(TroopQueueItem::getId, i -> i));

        List<TroopQueueItem> next = new ArrayList<>();
        int order = 0;
        for (TroopQueueItemDto dto : incoming) {
            TroopInfo info = TroopCatalog.lookup(tribe, dto.troopCode());
            if (info == null) {
                throw new IllegalArgumentException(
                        "Unknown troop code '" + dto.troopCode() + "' for tribe " + tribe
                                + " — pick a value from /api/troops/catalog?tribe=" + tribe + ".");
            }
            if (dto.quantity() <= 0) {
                // Bean-validation already enforces @Min(1), but the service-layer
                // check means a hand-crafted request can't bypass by skipping the
                // @Valid annotation on the controller.
                throw new IllegalArgumentException(
                        "Quantity must be positive for troop " + info.code() + ".");
            }
            // Always use the catalog's building, never the client's — avoids
            // drift if the catalog is tweaked between client and server deploys.
            TroopBuilding building = info.building();

            TroopQueueItem item;
            if (dto.id() != null && byId.containsKey(dto.id())) {
                item = byId.get(dto.id());
                item.update(info.code(), building, dto.quantity());
                item.setOrderIndex(order);
            } else {
                item = new TroopQueueItem(village, info.code(), building, dto.quantity(), order);
            }
            next.add(item);
            order++;
        }

        // Remove rows that didn't survive — orphanRemoval isn't set on this
        // collection (TroopQueueItem isn't owned by Village), so deleting is
        // our job. Compute the removed set explicitly for clarity.
        var keepIds = next.stream()
                .map(TroopQueueItem::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (TroopQueueItem oldItem : existing) {
            if (oldItem.getId() != null && !keepIds.contains(oldItem.getId())) {
                queueRepository.delete(oldItem);
            }
        }
        queueRepository.saveAll(next);

        return queueRepository.findAllByVillageIdOrderByOrderIndexAscIdAsc(villageId)
                .stream().map(TroopQueueItemDto::from).toList();
    }

    private Tribe resolveTribe(Account account) {
        return accountSettingsRepository.findByAccountId(account.getId())
                .map(AccountSettings::getTribe)
                .orElse(null);
    }

    /**
     * Drop the queue for every village on an account that's about to be deleted.
     * Runs inside the caller's transaction so a rollback keeps everything
     * consistent, matching {@code VillageAccountDeletionListener}'s contract.
     * <p>
     * {@code @Order} is set below the default so this listener runs BEFORE
     * the village listener — our rows FK on {@code village_id} and would trip
     * a constraint violation if the villages were gone first.
     */
    @EventListener
    @Order(0)
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        List<Village> villages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(event.accountId());
        for (Village v : villages) {
            queueRepository.deleteByVillageId(v.getId());
        }
        log.debug("Dropped troop queue rows for account {} ({} villages)",
                event.accountId(), villages.size());
    }
}
