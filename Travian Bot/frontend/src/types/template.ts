export const TEMPLATE_CATEGORIES = ['OFFENSE', 'DEFENSE', 'SUPPORT'] as const;
export type TemplateCategory = (typeof TEMPLATE_CATEGORIES)[number];

/**
 * Macro buildingType strings — these don't map to a single in-game building.
 * The backend expands each one at apply time into one queue row per matching
 * resource field of the village it's being applied to.
 */
export const TEMPLATE_MACROS = [
  'ALL_WOOD',
  'ALL_CLAY',
  'ALL_IRON',
  'ALL_CROP',
  'ALL_RESOURCES',
  'ALL_RESOURCES_NO_CROP',
] as const;
export type TemplateMacro = (typeof TEMPLATE_MACROS)[number];

/**
 * One ordered step in a village template. Templates no longer carry a slot —
 * the apply path resolves slots per village (existing building? first empty
 * infra slot? matching resource field?). {@code id} is absent for rows the
 * user just added in the editor; the backend mints it on save.
 * <p>
 * {@code slot} is kept on the wire for backward compatibility with rows
 * authored before the slotless refactor; the editor never writes a non-null
 * value to it for new rows.
 */
export interface TemplateStep {
  id: number | null;
  buildingType: string;
  slot: number | null;
  targetLevel: number;
}

export interface VillageTemplate {
  id: number;
  accountId: number;
  name: string;
  description: string | null;
  category: TemplateCategory;
  createdAt: string;
  updatedAt: string;
  steps: TemplateStep[];
}

export interface VillageTemplateCreate {
  accountId: number;
  name: string;
  description: string;
  category: TemplateCategory;
  steps: TemplateStep[];
}

export interface VillageTemplateUpdate {
  name: string;
  description: string;
  category: TemplateCategory;
  steps: TemplateStep[];
}

export interface CsvImportRequest {
  accountId: number;
  name: string;
  description: string;
  category: TemplateCategory;
  csv: string;
}

export interface ApplyResponse {
  added: number;
}
