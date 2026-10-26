import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useGeneralSettings } from './GeneralSettingsContext';
import {
  LANGUAGES,
  LANGUAGE_LABELS,
  THEMES,
  type GeneralSettings,
  type Language,
  type Theme,
} from '../../types/settings';

type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

/** Matches the backend {@code @Max(10000)} validator on {@code min/maxClickDelayMs}. */
const CLICK_DELAY_ABS_MAX_MS = 10_000;

/** Matches the backend {@code @Max(600)} validator on {@code maxActionsPerMinute}. */
const RATE_LIMIT_ABS_MAX = 600;

/**
 * Form for app-wide preferences. Saves via the context so the theme change
 * takes effect on {@code <html>} immediately.
 *
 * Organized into three visual sections:
 *  - <strong>Appearance</strong> — theme, language, desktop notifications.
 *  - <strong>Humanization</strong> — click-delay range used to avoid obvious
 *    bot fingerprints.
 *  - <strong>Scraping</strong> — default intervals for background scrapes
 *    (village resources, future account-level scrape loops).
 */
export default function GeneralSettingsForm() {
  const { t } = useTranslation();
  const { settings, loading, error, save } = useGeneralSettings();
  const [draft, setDraft] = useState<GeneralSettings | null>(null);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });

  useEffect(() => {
    if (settings) setDraft(settings);
  }, [settings]);

  if (loading) return <p className="muted">{t('common.loading')}</p>;
  if (error) return <p className="form__msg form__msg--error">{error}</p>;
  if (!draft) return null;

  const patch = <K extends keyof GeneralSettings>(key: K, value: GeneralSettings[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const submit = async () => {
    if (!draft) return;
    // Local guard for the min/max range — matches the backend @AssertTrue but gives a nicer
    // message than the generic "validation failed" bubble from Spring's ConstraintViolationException.
    if (draft.maxClickDelayMs < draft.minClickDelayMs) {
      setSaveState({
        kind: 'error',
        message: t('settings.validation.maxLessThanMin'),
      });
      return;
    }
    setSaveState({ kind: 'saving' });
    try {
      await save(draft);
      setSaveState({ kind: 'saved' });
    } catch (err) {
      setSaveState({ kind: 'error', message: err instanceof Error ? err.message : t('settings.saveFailed') });
    }
  };

  const clampDelay = (value: number) => {
    if (Number.isNaN(value)) return 1;
    return Math.min(CLICK_DELAY_ABS_MAX_MS, Math.max(1, value));
  };

  const clampRate = (value: number) => {
    if (Number.isNaN(value)) return 1;
    return Math.min(RATE_LIMIT_ABS_MAX, Math.max(1, value));
  };

  const clampInterval = (value: number, lo: number, hi: number) => {
    if (Number.isNaN(value)) return lo;
    return Math.min(hi, Math.max(lo, value));
  };

  return (
    <div className="form">
      <Section title={t('settings.appearance.title')}>
        <div className="form__field">
          <label>{t('settings.appearance.theme')}</label>
          <select value={draft.theme} onChange={(e) => patch('theme', e.target.value as Theme)}>
            {THEMES.map((v) => (
              <option key={v} value={v}>{t(`settings.appearance.theme.${v.toLowerCase()}`)}</option>
            ))}
          </select>
        </div>

        <div className="form__field">
          <label>{t('settings.appearance.language')}</label>
          <select value={draft.language} onChange={(e) => patch('language', e.target.value as Language)}>
            {LANGUAGES.map((v) => (
              <option key={v} value={v}>{LANGUAGE_LABELS[v]}</option>
            ))}
          </select>
        </div>

        <label className="form__check">
          <input
            type="checkbox"
            checked={draft.desktopNotificationsEnabled}
            onChange={(e) => patch('desktopNotificationsEnabled', e.target.checked)}
          />
          <span>{t('settings.appearance.desktopNotifications')}</span>
        </label>

        <label className="form__check">
          <input
            type="checkbox"
            checked={draft.soundAlertsEnabled}
            onChange={(e) => patch('soundAlertsEnabled', e.target.checked)}
          />
          <span>{t('settings.appearance.soundAlerts')}</span>
        </label>
        <p className="muted" style={{ margin: '0.25rem 0 0 1.6rem', fontSize: '0.8rem' }}>
          {t('settings.appearance.soundAlertsHint')}
        </p>
      </Section>

      <Section
        title={t('settings.humanization.title')}
        hint={t('settings.humanization.hint')}
      >
        <div className="form__field">
          <label>{t('settings.humanization.clickDelay')}</label>
          <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.85rem' }}>
            {t('settings.humanization.clickDelayHint', { max: CLICK_DELAY_ABS_MAX_MS })}
          </p>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', flexDirection: 'column', fontSize: '0.85rem' }}>
              <span className="muted">{t('settings.humanization.min')}</span>
              <input
                type="number"
                min={1}
                max={CLICK_DELAY_ABS_MAX_MS}
                value={draft.minClickDelayMs}
                onChange={(e) => {
                  patch('minClickDelayMs', clampDelay(Number.parseInt(e.target.value, 10)));
                }}
                style={{ width: '7rem' }}
              />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', fontSize: '0.85rem' }}>
              <span className="muted">{t('settings.humanization.max')}</span>
              <input
                type="number"
                min={1}
                max={CLICK_DELAY_ABS_MAX_MS}
                value={draft.maxClickDelayMs}
                onChange={(e) => {
                  patch('maxClickDelayMs', clampDelay(Number.parseInt(e.target.value, 10)));
                }}
                style={{ width: '7rem' }}
              />
            </label>
          </div>
        </div>

        <div className="form__field">
          <label>{t('settings.humanization.rateLimit')}</label>
          <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.85rem' }}>
            {t('settings.humanization.rateLimitHint')}
          </p>
          <input
            type="number"
            min={1}
            max={RATE_LIMIT_ABS_MAX}
            value={draft.maxActionsPerMinute}
            onChange={(e) => {
              patch('maxActionsPerMinute', clampRate(Number.parseInt(e.target.value, 10)));
            }}
            style={{ width: '7rem' }}
          />
        </div>
      </Section>

      <Section
        title={t('settings.scraping.title')}
        hint={t('settings.scraping.hint')}
      >
        <div className="form__field">
          <label>{t('settings.scraping.defaultCheck')}</label>
          <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.85rem' }}>
            {t('settings.scraping.defaultCheckHint')}
          </p>
          <input
            type="number"
            min={1}
            value={draft.defaultCheckIntervalMinutes}
            onChange={(e) => {
              patch('defaultCheckIntervalMinutes', clampInterval(Number.parseInt(e.target.value, 10), 1, 1440));
            }}
            style={{ width: '7rem' }}
          />
        </div>

        {/*
         * Village scrape interval moved to per-village settings (Villages → Settings tab).
         * Reason: a single global knob doesn't fit real play — a capital under attack wants
         * 2–5 min polling while a quiet support village is fine at 30–60.
         */}

        <label className="form__check" style={{ marginTop: '0.75rem' }}>
          <input
            type="checkbox"
            checked={draft.villageScrapingEnabled}
            onChange={(e) => patch('villageScrapingEnabled', e.target.checked)}
          />
          <span>{t('settings.scraping.villageScrapingEnabled')}</span>
        </label>
        <p className="muted" style={{ margin: '0.25rem 0 0 1.6rem', fontSize: '0.8rem' }}>
          {t('settings.scraping.villageScrapingEnabledHint')}
        </p>

        <label className="form__check" style={{ marginTop: '0.75rem' }}>
          <input
            type="checkbox"
            checked={draft.autoReloginEnabled}
            onChange={(e) => patch('autoReloginEnabled', e.target.checked)}
          />
          <span>{t('settings.scraping.autoRelogin')}</span>
        </label>
        <p className="muted" style={{ margin: '0.25rem 0 0 1.6rem', fontSize: '0.8rem' }}>
          {t('settings.scraping.autoReloginHint')}
        </p>
      </Section>

      <div className="settings-savebar">
        <div className="settings-savebar__msg">
          {saveState.kind === 'saving' && <span className="muted">{t('settings.saving')}</span>}
          {saveState.kind === 'saved' && <span className="pill pill--success">{t('settings.saved')}</span>}
          {saveState.kind === 'error' && (
            <span className="form__msg form__msg--error" style={{ margin: 0 }}>
              {saveState.message}
            </span>
          )}
        </div>
        <button type="button" className="btn btn--primary" onClick={submit} disabled={saveState.kind === 'saving'}>
          {t('settings.save')}
        </button>
      </div>
    </div>
  );
}

/** Visual grouping header — purely a layout helper, no state of its own. */
function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="settings-section">
      <h3 className="settings-section__title">{title}</h3>
      {hint && <p className="settings-section__hint">{hint}</p>}
      <div className="settings-section__body">{children}</div>
    </div>
  );
}
