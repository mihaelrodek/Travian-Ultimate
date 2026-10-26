import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { telegramApi } from '../api/telegram';
import type { TelegramSettings, TelegramTestResult } from '../types/telegram';

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'error'; message: string };

type TestState =
  | { kind: 'idle' }
  | { kind: 'testing' }
  | { kind: 'done'; result: TelegramTestResult };

/**
 * Telegram integration settings page. Same structure as the Discord page:
 *   - Connection — master switch + bot token + chat id, gated so everything
 *     below is disabled until both are set.
 *   - Notification triggers — per-event toggles (shared labels with Discord,
 *     since the events are channel-agnostic).
 *
 * Save is one atomic PUT; "Send test" bypasses the master switch so the user
 * can verify the token/chat before flipping the integration on.
 */
export default function TelegramPage() {
  const { t } = useTranslation();
  const [settings, setSettings] = useState<TelegramSettings | null>(null);
  const [draft, setDraft] = useState<TelegramSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [testState, setTestState] = useState<TestState>({ kind: 'idle' });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const s = await telegramApi.get();
        if (cancelled) return;
        setSettings(s);
        setDraft(s);
        setLoadError(null);
      } catch (err) {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : 'Failed to load Telegram settings');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) return <p className="muted">{t('common.loading')}</p>;
  if (loadError) return <p className="form__msg form__msg--error">{loadError}</p>;
  if (!draft || !settings) return null;

  const patch = <K extends keyof TelegramSettings>(key: K, value: TelegramSettings[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const dirty = JSON.stringify(draft) !== JSON.stringify(settings);

  // Both credentials required before anything downstream is meaningful.
  const configured =
    draft.botToken != null && draft.botToken.trim().length > 0 &&
    draft.chatId != null && draft.chatId.trim().length > 0;

  const submit = async () => {
    if (!draft) return;
    setSaveState({ kind: 'saving' });
    try {
      const saved = await telegramApi.update({
        enabled: draft.enabled,
        botToken: draft.botToken,
        chatId: draft.chatId,
        notifyIncomingAttack: draft.notifyIncomingAttack,
        notifyIncomingRaid: draft.notifyIncomingRaid,
        notifyReturnCatch: draft.notifyReturnCatch,
        notifyCaptcha: draft.notifyCaptcha,
        notifySessionLost: draft.notifySessionLost,
        notifyResourceOverflow: draft.notifyResourceOverflow,
      });
      setSettings(saved);
      setDraft(saved);
      setSaveState({ kind: 'saved' });
    } catch (err) {
      setSaveState({
        kind: 'error',
        message: err instanceof Error ? err.message : t('settings.saveFailed'),
      });
    }
  };

  const onTest = async () => {
    if (!configured) return;
    setTestState({ kind: 'testing' });
    try {
      const result = await telegramApi.test();
      setTestState({ kind: 'done', result });
    } catch (err) {
      setTestState({
        kind: 'done',
        result: { ok: false, message: err instanceof Error ? err.message : 'Test failed' },
      });
    }
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader title="Telegram" subtitle={t('telegram.subtitle')} />
      </div>

      <Card>
        <div className="form">
          <Section title={t('telegram.connection.title')} hint={t('telegram.connection.hint')}>
            <label className="form__check">
              <input
                type="checkbox"
                checked={draft.enabled}
                onChange={(e) => patch('enabled', e.target.checked)}
                disabled={!configured}
              />
              <span>{t('telegram.connection.enabled')}</span>
            </label>
            <p className="muted" style={{ margin: '0.25rem 0 0.75rem 1.6rem', fontSize: '0.8rem' }}>
              {t('telegram.connection.enabledHint')}
            </p>

            <div className="form__field">
              <label>{t('telegram.connection.botToken')}</label>
              <input
                type="text"
                value={draft.botToken ?? ''}
                placeholder="123456789:ABCdef..."
                onChange={(e) => patch('botToken', e.target.value.trim() === '' ? null : e.target.value)}
                style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.8rem' }}>
                {t('telegram.connection.botTokenHint')}
              </p>
            </div>

            <div className="form__field">
              <label>{t('telegram.connection.chatId')}</label>
              <input
                type="text"
                value={draft.chatId ?? ''}
                placeholder="123456789 or @channelname"
                onChange={(e) => patch('chatId', e.target.value.trim() === '' ? null : e.target.value)}
                style={{ width: '100%', maxWidth: '24rem', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.8rem' }}>
                {t('telegram.connection.chatIdHint')}
              </p>
            </div>

            <div style={{ marginTop: '0.75rem', display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void onTest()}
                disabled={!configured || dirty || testState.kind === 'testing'}
                title={
                  !configured
                    ? t('telegram.test.disabledNoConfig')
                    : dirty
                    ? t('telegram.test.disabledDirty')
                    : t('telegram.test.tooltip')
                }
              >
                {testState.kind === 'testing' ? t('telegram.test.sending') : t('telegram.test.button')}
              </button>
              {testState.kind === 'done' && (
                <span
                  className={testState.result.ok ? 'form__msg form__msg--ok' : 'form__msg form__msg--error'}
                  style={{ margin: 0 }}
                >
                  {testState.result.message}
                </span>
              )}
            </div>
          </Section>

          <Section title={t('discord.triggers.title')} hint={t('discord.triggers.hint')}>
            <ToggleRow
              label={t('discord.triggers.incomingAttack')}
              description={t('discord.triggers.incomingAttackHint')}
              checked={draft.notifyIncomingAttack}
              disabled={!configured}
              onChange={(v) => patch('notifyIncomingAttack', v)}
            />
            <ToggleRow
              label={t('discord.triggers.incomingRaid')}
              description={t('discord.triggers.incomingRaidHint')}
              checked={draft.notifyIncomingRaid}
              disabled={!configured}
              onChange={(v) => patch('notifyIncomingRaid', v)}
            />
            <ToggleRow
              label={t('discord.triggers.returnCatch')}
              description={t('discord.triggers.returnCatchHint')}
              checked={draft.notifyReturnCatch}
              disabled={!configured}
              onChange={(v) => patch('notifyReturnCatch', v)}
            />
            <ToggleRow
              label={t('discord.triggers.captcha')}
              description={t('discord.triggers.captchaHint')}
              checked={draft.notifyCaptcha}
              disabled={!configured}
              onChange={(v) => patch('notifyCaptcha', v)}
            />
            <ToggleRow
              label={t('discord.triggers.sessionLost')}
              description={t('discord.triggers.sessionLostHint')}
              checked={draft.notifySessionLost}
              disabled={!configured}
              onChange={(v) => patch('notifySessionLost', v)}
            />
            <ToggleRow
              label={t('discord.triggers.resourceOverflow')}
              description={t('discord.triggers.resourceOverflowHint')}
              checked={draft.notifyResourceOverflow}
              disabled={!configured}
              onChange={(v) => patch('notifyResourceOverflow', v)}
            />
          </Section>

          <div className="settings-savebar">
            <div className="settings-savebar__msg">
              {saveState.kind === 'saving' && <span className="muted">{t('settings.saving')}</span>}
              {saveState.kind === 'saved' && !dirty && (
                <span className="pill pill--success">{t('settings.saved')}</span>
              )}
              {saveState.kind === 'error' && (
                <span className="form__msg form__msg--error" style={{ margin: 0 }}>
                  {saveState.message}
                </span>
              )}
            </div>
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => void submit()}
              disabled={saveState.kind === 'saving' || !dirty}
            >
              {t('settings.save')}
            </button>
          </div>
        </div>
      </Card>
    </div>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  disabled,
  onChange,
}: {
  label: string;
  description: string;
  checked: boolean;
  disabled: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div style={{ marginBottom: '0.5rem' }}>
      <label className="form__check">
        <input
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span>{label}</span>
      </label>
      <p className="muted" style={{ margin: '0.15rem 0 0 1.6rem', fontSize: '0.8rem' }}>
        {description}
      </p>
    </div>
  );
}

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
