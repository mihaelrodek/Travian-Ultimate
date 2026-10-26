package com.travianbot.intel.alliance.api;

import com.travianbot.common.error.NotFoundException;
import com.travianbot.intel.alliance.api.dto.AllianceDetailResponse;
import com.travianbot.intel.alliance.api.dto.AlliancePageResponse;
import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.domain.MapAllianceMember;
import com.travianbot.intel.alliance.persistence.MapAllianceMemberRepository;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.intel.alliance.service.AllianceDetailScraperService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * REST surface for the alliance-intel feature. Lives under the existing
 * server-scoped intel namespace so the URL shape matches the inactive-search
 * endpoints.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/intel/alliances")
public class AllianceController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Sort fields the client is allowed to pass — anything else falls back to memberCount. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "name", "memberCount", "totalPopulation", "averagePopulation", "lastSeenAt"
    );

    private final MapAllianceRepository allianceRepository;
    private final MapAllianceMemberRepository memberRepository;
    private final AllianceDetailScraperService scraperService;

    public AllianceController(MapAllianceRepository allianceRepository,
                              MapAllianceMemberRepository memberRepository,
                              AllianceDetailScraperService scraperService) {
        this.allianceRepository = allianceRepository;
        this.memberRepository = memberRepository;
        this.scraperService = scraperService;
    }

    @GetMapping
    public AlliancePageResponse list(
            @PathVariable Long serverId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer minMembers,
            @RequestParam(required = false) Integer maxMembers,
            @RequestParam(required = false, defaultValue = "memberCount") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        if (safeSize == 0) safeSize = DEFAULT_PAGE_SIZE;

        String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "memberCount";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, safeSortBy));

        Specification<MapAlliance> spec = buildSpec(serverId, search, minMembers, maxMembers);
        Page<MapAlliance> result = allianceRepository.findAll(spec, pageable);
        return AlliancePageResponse.from(result);
    }

    @GetMapping("/{aid}")
    public AllianceDetailResponse get(
            @PathVariable Long serverId,
            @PathVariable Long aid
    ) {
        MapAlliance alliance = allianceRepository.findFirstByServerIdAndTravianAid(serverId, aid)
                .orElseThrow(() -> new NotFoundException("Alliance not found: aid=" + aid + " on server " + serverId));
        List<MapAllianceMember> members = memberRepository
                .findAllByMapAllianceIdOrderByPopulationDesc(alliance.getId());
        return AllianceDetailResponse.from(alliance, members);
    }

    /**
     * Trigger an on-demand scrape of {@code /allianz.php?aid=N}. Respects the
     * 24h cache unless {@code force=true}. Returns the (possibly cached)
     * detail row; never returns 404 — if the alliance row doesn't exist yet,
     * the user is told to run a map.sql sync first.
     */
    @PostMapping("/{aid}/refresh-detail")
    public AllianceDetailResponse refreshDetail(
            @PathVariable Long serverId,
            @PathVariable Long aid,
            @RequestParam(required = false, defaultValue = "false") boolean force
    ) {
        MapAlliance alliance = scraperService.scrapeDetail(serverId, aid, force)
                .orElseThrow(() -> new NotFoundException(
                        "Alliance aid=" + aid + " not tracked on server " + serverId
                                + " yet — run a map.sql sync first"));
        List<MapAllianceMember> members = memberRepository
                .findAllByMapAllianceIdOrderByPopulationDesc(alliance.getId());
        return AllianceDetailResponse.from(alliance, members);
    }

    /**
     * Spec for the list endpoint: server-scoped, optional case-insensitive name
     * substring, optional member-count range. {@code search} matches anywhere
     * in the name to mirror the inactive-search alliance-include behaviour.
     */
    private Specification<MapAlliance> buildSpec(Long serverId, String search,
                                                 Integer minMembers, Integer maxMembers) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("serverId"), serverId));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.like(cb.lower(root.get("name")), pattern));
            }
            if (minMembers != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("memberCount"), minMembers));
            }
            if (maxMembers != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("memberCount"), maxMembers));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
