import { api } from './client';
import type {
  TrainConfigRequest,
  TrainConfigResponse,
  TroopCatalogEntry,
} from '../types/troops';

/**
 * Catalogue + per-village training-config endpoints. The catalogue is tribe-
 * scoped so the UI only shows the troops the player's tribe can actually
 * train. Per-village min/max quantities live behind {@code train-config}; the
 * scheduler picks a random qty in that range each cycle.
 */
export const troopsApi = {
  catalog: (tribe: string) =>
    api.get<TroopCatalogEntry[]>(`/troops/catalog?tribe=${encodeURIComponent(tribe)}`),

  getTrainConfig: (villageId: number) =>
    api.get<TrainConfigResponse>(`/villages/${villageId}/train-config`),

  putTrainConfig: (villageId: number, body: TrainConfigRequest) =>
    api.put<TrainConfigResponse>(`/villages/${villageId}/train-config`, body),
};
