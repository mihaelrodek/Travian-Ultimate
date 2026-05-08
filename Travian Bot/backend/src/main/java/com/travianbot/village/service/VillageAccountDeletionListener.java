package com.travianbot.village.service;

import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.persistence.VillageSettingsRepository;
import com.travianbot.village.persistence.VillageTrainConfigRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drops all scrape state (resource fields, buildings), settings rows, and then all
 * villages (and their queue items, via orphanRemoval / cascade) for an account
 * that's about to be deleted. Runs inside the same transaction as the account
 * deletion so a rollback keeps everything consistent.
 */
@Component
public class VillageAccountDeletionListener {

    private final VillageRepository villages;
    private final ResourceFieldRepository resourceFields;
    private final BuildingRepository buildings;
    private final VillageSettingsRepository villageSettings;
    private final VillageTrainConfigRepository trainConfigs;

    public VillageAccountDeletionListener(
            VillageRepository villages,
            ResourceFieldRepository resourceFields,
            BuildingRepository buildings,
            VillageSettingsRepository villageSettings,
            VillageTrainConfigRepository trainConfigs
    ) {
        this.villages = villages;
        this.resourceFields = resourceFields;
        this.buildings = buildings;
        this.villageSettings = villageSettings;
        this.trainConfigs = trainConfigs;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        // Scrape data + settings have FKs to village_id, not account_id — need the
        // village list first to know which rows to remove before villages go.
        // village_settings.village_id is NOT NULL; without this the village
        // delete below would fail with a constraint error.
        List<Village> accountVillages = villages.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(event.accountId());
        for (Village v : accountVillages) {
            villageSettings.deleteByVillageId(v.getId());
            trainConfigs.deleteByVillageId(v.getId());
            resourceFields.deleteByVillageId(v.getId());
            buildings.deleteByVillageId(v.getId());
        }
        villages.deleteAllByAccountId(event.accountId());
    }
}
