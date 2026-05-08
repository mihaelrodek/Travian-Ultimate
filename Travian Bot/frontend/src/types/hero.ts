/**
 * Types mirroring the backend hero API contract
 * (see {@code HeroResponse} in the backend).
 *
 * Every field mirrors its Java counterpart 1:1. Enums are serialized as
 * strings by Jackson, so we model them as narrow string-literal unions here.
 */

export type HeroStatus =
  | 'HOME'
  | 'MOVING'
  | 'IN_ADVENTURE'
  | 'DEAD'
  | 'REVIVING'
  | 'UNKNOWN';

export type HeroProductionType =
  | 'WOOD'
  | 'CLAY'
  | 'IRON'
  | 'CROP'
  | 'ALL_RESOURCES'
  | 'UNKNOWN';

export type HeroItemCategory =
  | 'HELMET'
  | 'WEAPON'
  | 'ARMOR'
  | 'LEFT_HAND'
  | 'HORSE'
  | 'BOOTS'
  | 'BANDAGE'
  | 'OINTMENT'
  | 'SCROLL'
  | 'BUCKET'
  | 'CAGE'
  | 'ARTWORK'
  | 'BOOK_OF_WISDOM'
  | 'TABLET_OF_LAW'
  | 'SMALL_BANDAGE'
  | 'RESOURCE_BONUS'
  | 'CROP_BONUS'
  | 'EXP_BONUS'
  | 'CONSUMABLE'
  | 'OTHER';

export type AdventureDifficulty = 'NORMAL' | 'HARD' | 'UNKNOWN';

export interface HeroItem {
  id: number;
  travianItemId: number | null;
  name: string | null;
  category: HeroItemCategory;
  amount: number;
  equipped: boolean;
  slot: number | null;
  imageUrl: string | null;
}

export interface HeroAdventure {
  id: number;
  targetX: number;
  targetY: number;
  difficulty: AdventureDifficulty;
  endAt: string | null;
  mapId: number | null;
}

export interface Hero {
  id: number;
  accountId: number;
  name: string | null;
  level: number;
  experience: number;
  experienceForNext: number | null;
  health: number;
  regenPerHour: number;
  freePoints: number;
  pointsFightingStrength: number;
  pointsOffBonus: number;
  pointsDefBonus: number;
  pointsResources: number;
  adventurePoints: number;
  status: HeroStatus;
  productionType: HeroProductionType;
  homeVillageDid: number | null;
  currentVillageDid: number | null;
  reviveAt: string | null;
  lastScrapedAt: string | null;
  items: HeroItem[];
  adventures: HeroAdventure[];
}
