/**
 * SANDBOX TYPE SHIMS — delete this file after running `npm install`.
 *
 * The development sandbox that generates this code cannot reach the public npm
 * registry, so `i18next`, `react-i18next` and the language detector can't be
 * installed here. These loose declarations let `tsc -b` verify our own code
 * against the subset of the i18next API we actually use.
 *
 * After you run `npm install` in frontend/, the real package types from
 * node_modules will take over — this shim becomes dead weight and can be
 * removed. Keeping it around is harmless (TypeScript merges ambient module
 * declarations, and every member here is widely typed), but deleting it gives
 * you full IDE autocomplete on the real API surface.
 */

declare module 'i18next' {
  export type TFunction = (key: string, options?: Record<string, unknown>) => string;

  export interface i18nLike {
    language: string;
    changeLanguage(lng: string): Promise<TFunction>;
    use(plugin: unknown): i18nLike;
    init(options?: Record<string, unknown>): Promise<TFunction>;
    t: TFunction;
  }

  const i18n: i18nLike;
  export default i18n;
}

declare module 'react-i18next' {
  import type { TFunction } from 'i18next';
  export const initReactI18next: unknown;
  export function useTranslation(): { t: TFunction; i18n: import('i18next').i18nLike };
}

declare module 'i18next-browser-languagedetector' {
  const LanguageDetector: unknown;
  export default LanguageDetector;
}
