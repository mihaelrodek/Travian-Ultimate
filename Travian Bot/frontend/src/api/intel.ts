import { api } from './client';
import type {
  AllianceDetail,
  AlliancePage,
  AllianceSearchFilters,
  CropperCandidate,
  CropperReclassifyResponse,
  CropperSearchParams,
  InactivePlayerDetail,
  InactivePlayerPage,
  InactiveSearchFilters,
  IntelSyncResponse,
  WorldMap,
} from '../types/intel';

/**
 * REST client for the intel / inactive-search module. Mirrors the shape of
 * {@code villagesApi} so the page hook code looks familiar.
 *
 * All endpoints here are **server-scoped**: the map.sql dataset belongs to a
 * Travian world, not to an individual account, so multiple accounts on the
 * same world share one set of results. The one exception is
 * {@link quickAddToFarmlist}, which targets a farmlist (owned by an account)
 * and therefore still takes {@code accountId}.
 */
export const intelApi = {
  /** Trigger an immediate map.sql sync for this server. */
  sync: (serverId: number) =>
    api.post<IntelSyncResponse>(`/servers/${serverId}/intel/sync`),

  /** Every settled village on the server, for the interactive world map (MAP1). */
  worldMap: (serverId: number) =>
    api.get<WorldMap>(`/servers/${serverId}/intel/map`),

  /** Paginated, filtered search over the tracked player table for this server. */
  searchInactives: (serverId: number, filters: InactiveSearchFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.minIdleDays != null) params.set('minIdleDays', String(filters.minIdleDays));
    if (filters.maxPopulation != null) params.set('maxPopulation', String(filters.maxPopulation));
    if (filters.minPopulation != null) params.set('minPopulation', String(filters.minPopulation));
    if (filters.maxPopulationIncrease != null) params.set('maxPopulationIncrease', String(filters.maxPopulationIncrease));
    if (filters.minVillageCount != null) params.set('minVillageCount', String(filters.minVillageCount));
    if (filters.maxVillageCount != null) params.set('maxVillageCount', String(filters.maxVillageCount));
    if (filters.xMin != null) params.set('xMin', String(filters.xMin));
    if (filters.xMax != null) params.set('xMax', String(filters.xMax));
    if (filters.yMin != null) params.set('yMin', String(filters.yMin));
    if (filters.yMax != null) params.set('yMax', String(filters.yMax));
    if (filters.nearVillageId != null) params.set('nearVillageId', String(filters.nearVillageId));
    if (filters.maxDistance != null) params.set('maxDistance', String(filters.maxDistance));
    if (filters.tribeIds && filters.tribeIds.length > 0) {
      params.set('tribeIds', filters.tribeIds.join(','));
    }
    if (filters.allianceExclude) params.set('allianceExclude', filters.allianceExclude);
    if (filters.allianceInclude) params.set('allianceInclude', filters.allianceInclude);
    if (filters.allianceTravianId != null) params.set('allianceTravianId', String(filters.allianceTravianId));
    if (filters.lastSeenWithinDays != null) params.set('lastSeenWithinDays', String(filters.lastSeenWithinDays));
    if (filters.inactiveOnly != null) params.set('inactiveOnly', String(filters.inactiveOnly));
    if (filters.sortBy) params.set('sortBy', filters.sortBy);
    if (filters.sortDir) params.set('sortDir', filters.sortDir);
    if (filters.page != null) params.set('page', String(filters.page));
    if (filters.size != null) params.set('size', String(filters.size));
    const qs = params.toString();
    return api.get<InactivePlayerPage>(
      `/servers/${serverId}/intel/inactives${qs ? '?' + qs : ''}`,
    );
  },

  /** Detail view for a single player by Travian uid on the given server. */
  getPlayer: (serverId: number, uid: number) =>
    api.get<InactivePlayerDetail>(`/servers/${serverId}/intel/players/${uid}`),

  /**
   * Per-village population history (newest first), capped at `points`.
   * Drives the inline sparkline in the player detail modal.
   */
  villagePopulationHistory: (serverId: number, mapVillageId: number, points = 30) =>
    api.get<import('../types/intel').VillagePopulationPoint[]>(
      `/servers/${serverId}/intel/villages/${mapVillageId}/population-history?points=${points}`,
    ),

  /**
   * Quick-add the player's villages to a farmlist.
   *
   * NOTE — this one is **account-scoped**, not server-scoped, because farmlists
   * belong to accounts (only the account that owns the farmlist can raid from
   * it). The page that calls this needs to resolve which account to act on
   * (typically the owner of the pivot village).
   *
   * TODO — wire this up once the farmlist module exposes a "quick-add by uid"
   * endpoint. Today the backend returns 404 which the page surfaces as an
   * inline error.
   */
  quickAddToFarmlist: (accountId: number, uid: number) =>
    api.post<void>(`/accounts/${accountId}/farmlists/quick-add`, { travianUid: uid }),

  /**
   * Cropper-finder search. Returns up to 200 candidates near the pivot
   * village, sorted by ascending distance. {@code classifications=[]} means
   * "use the backend's default set" (everything except UNKNOWN).
   */
  findCroppers: (serverId: number, params: CropperSearchParams) => {
    const qs = new URLSearchParams();
    qs.set('nearVillageId', String(params.nearVillageId));
    if (params.maxDistance != null) qs.set('maxDistance', String(params.maxDistance));
    if (params.classifications && params.classifications.length > 0) {
      qs.set('classifications', params.classifications.join(','));
    }
    if (params.includeOccupied != null) qs.set('includeOccupied', String(params.includeOccupied));
    return api.get<CropperCandidate[]>(
      `/servers/${serverId}/intel/croppers?${qs.toString()}`,
    );
  },

  /**
   * Trigger an immediate re-classification of every {@code MapVillage} on
   * this server — useful after the heuristic itself is tuned or when the user
   * wants the buckets refreshed without waiting for the daily sync.
   */
  reclassifyCroppers: (serverId: number) =>
    api.post<CropperReclassifyResponse>(`/servers/${serverId}/intel/croppers/reclassify`),

  /**
   * Paginated, filtered search over the aggregated {@code MapAlliance} table
   * for this server. The aggregate is rebuilt as part of the daily
   * {@code map.sql} sync — see {@code AllianceAggregator}.
   */
  listAlliances: (serverId: number, filters: AllianceSearchFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.search) params.set('search', filters.search);
    if (filters.minMembers != null) params.set('minMembers', String(filters.minMembers));
    if (filters.maxMembers != null) params.set('maxMembers', String(filters.maxMembers));
    if (filters.sortBy) params.set('sortBy', filters.sortBy);
    if (filters.sortDir) params.set('sortDir', filters.sortDir);
    if (filters.page != null) params.set('page', String(filters.page));
    if (filters.size != null) params.set('size', String(filters.size));
    const qs = params.toString();
    return api.get<AlliancePage>(
      `/servers/${serverId}/intel/alliances${qs ? '?' + qs : ''}`,
    );
  },

  /**
   * Detail view for a single alliance by Travian aid on the given server.
   * Always returns the cached row — call {@link refreshAllianceDetail} to
   * trigger a live scrape first if you need fresh description / bonuses /
   * roster.
   */
  getAlliance: (serverId: number, aid: number) =>
    api.get<AllianceDetail>(`/servers/${serverId}/intel/alliances/${aid}`),

  /**
   * Trigger the on-demand {@code AllianceDetailScraper} for a single alliance
   * and return the freshly-scraped detail. {@code force=true} re-scrapes even
   * if the cached row was scraped recently; {@code force=false} (the default)
   * lets the backend short-circuit when the cache is still warm.
   */
  refreshAllianceDetail: (serverId: number, aid: number, force = false) =>
    api.post<AllianceDetail>(
      `/servers/${serverId}/intel/alliances/${aid}/refresh-detail?force=${force ? 'true' : 'false'}`,
    ),
};
