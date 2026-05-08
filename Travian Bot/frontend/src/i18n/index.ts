import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import type { Language } from '../types/settings';
import en from './en.json';
import hr from './hr.json';

/**
 * i18n setup.
 *
 * Resources are bundled as JSON so the user can edit translations without
 * touching any .ts code. One namespace ("translation") keeps key lookup trivial
 * — for a codebase this size, a flat tree of nested keys is plenty.
 *
 * The language detector picks a starting locale from (in order): the value
 * stored in localStorage under {@code i18nextLng}, the browser's
 * {@code navigator.language}, and finally the {@code fallbackLng}. The
 * authoritative source is still {@link /features/settings/GeneralSettingsContext}:
 * whenever the user saves Settings, that context calls
 * {@link setAppLanguage} which flips {@code i18n.language} + persists it.
 *
 * If you add a new language:
 *  1. Add the code to {@code Language} in {@code types/settings.ts}.
 *  2. Drop a new JSON file next to this one and import it into {@code resources}.
 *  3. Add the backend enum value in {@code settings/domain/Language.java}.
 */

void i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      hr: { translation: hr },
    },
    fallbackLng: 'en',
    supportedLngs: ['en', 'hr'],
    nonExplicitSupportedLngs: true, // map "en-GB", "hr-HR" → "en", "hr"
    interpolation: { escapeValue: false }, // React already escapes
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
    },
  });

/**
 * Called from {@code GeneralSettingsContext} after a save. The settings payload
 * holds the language as an uppercase enum (EN / HR), but i18next uses lowercase
 * BCP-47 codes for its internal lookups — keep the mapping centralized here so
 * callers don't need to know the casing convention.
 */
export function setAppLanguage(lang: Language): void {
  void i18n.changeLanguage(lang.toLowerCase());
}

export default i18n;
