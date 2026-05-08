package com.travianbot.troops.api;

import com.travianbot.account.domain.Tribe;
import com.travianbot.troops.domain.TroopBuilding;
import com.travianbot.troops.domain.TroopCatalog;
import com.travianbot.troops.domain.TroopCatalog.TroopInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the tribe-keyed troop catalogue so the frontend can render dropdowns
 * without duplicating the game-data table. Returns one tribe per request so the
 * client can react to an account's {@code AccountSettings.tribe} without paging
 * through six tribes it doesn't care about.
 */
@RestController
@RequestMapping("/api/troops")
public class TroopCatalogController {

    public record TroopCatalogEntry(
            String code,
            String displayName,
            TroopBuilding building,
            int troopSlot
    ) {
        static TroopCatalogEntry from(TroopInfo info) {
            return new TroopCatalogEntry(
                    info.code(), info.displayName(), info.building(), info.troopSlot());
        }
    }

    @GetMapping("/catalog")
    public List<TroopCatalogEntry> catalog(@RequestParam Tribe tribe) {
        return TroopCatalog.forTribe(tribe).stream()
                .map(TroopCatalogEntry::from)
                .toList();
    }
}
