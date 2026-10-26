import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { discordApi } from '../api/discord';
import type { DiscordSettings, DiscordTestResult } from '../types/discord';

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'error'; message: string };

type TestState =
  | { kind: 'idle' }
  | { kind: 'testing' }
  | { kind: 'done'; result: DiscordTestResult };

/**
 * Dedicated configuration page for the Discord integration.
 *
 * Layout mirrors the conceptual structure of the data:
 *   - <strong>Webhook</strong> — master switch + the URL Discord generates
 *     for the channel. Without a URL nothing else matters; the page makes
 *     that explicit by greying everything below until the URL is set.
 *   - <strong>Bot identity</strong> — sender name + avatar URL. Optional.
 *   - <strong>Mentions</strong> — optional user / role ping prefix the
 *     notifier prepends to every alert.
 *   - <strong>Notification triggers</strong> — per-event toggles so the
 *     user can mute the chatty patterns (raids) without losing the
 *     important ones (attacks, captcha).
 *
 * Save is one atomic PUT — no per-field auto-save. There's also a
 * "Send test" button that bypasses the master switch so the user can
 * verify the URL works before flipping {@code enabled} on.
 */
export default function DiscordPage() {
  const { t } = useTranslation();
  const [settings, setSettings] = useState<DiscordSettings | null>(null);
  const [draft, setDraft] = useState<DiscordSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [testState, setTestState] = useState<TestState>({ kind: 'idle' });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const s = await discordApi.get();
        if (cancelled) return;
        setSettings(s);
        setDraft(s);
        setLoadError(null);
      } catch (err) {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : 'Failed to load Discord settings');
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

  const patch = <K extends keyof DiscordSettings>(key: K, value: DiscordSettings[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const dirty = JSON.stringify(draft) !== JSON.stringify(settings);

  // Webhook URL gate — every section past "Webhook" is meaningless until a
  // URL is configured. Disabling the inputs prevents fiddly half-configured
  // states (e.g. enabled = true but URL = empty → notifier silently no-ops).
  const hasUrl = draft.webhookUrl != null && draft.webhookUrl.trim().length > 0;

  const submit = async () => {
    if (!draft) return;
    setSaveState({ kind: 'saving' });
    try {
      const saved = await discordApi.update({
        enabled: draft.enabled,
        webhookUrl: draft.webhookUrl,
        botUsername: draft.botUsername,
        botAvatarUrl: draft.botAvatarUrl,
        mentionUserId: draft.mentionUserId,
        mentionRoleId: draft.mentionRoleId,
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
    if (!hasUrl) return;
    setTestState({ kind: 'testing' });
    try {
      const result = await discordApi.test();
      setTestState({ kind: 'done', result });
    } catch (err) {
      setTestState({
        kind: 'done',
        result: {
          ok: false,
          message: err instanceof Error ? err.message : 'Test failed',
        },
      });
    }
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Discord"
        subtitle="Webhook integracija + obavijesti."
      />
      </div>

      <Card>
        <div className="form">
          <Section title={t('discord.webhook.title')} hint={t('discord.webhook.hint')}>
            <label className="form__check">
              <input
                type="checkbox"
                checked={draft.enabled}
                onChange={(e) => patch('enabled', e.target.checked)}
                disabled={!hasUrl}
              />
              <span>{t('discord.webhook.enabled')}</span>
            </label>
            <p className="muted" style={{ margin: '0.25rem 0 0.75rem 1.6rem', fontSize: '0.8rem' }}>
              {t('discord.webhook.enabledHint')}
            </p>

            <div className="form__field">
              <label>{t('discord.webhook.url')}</label>
              <input
                type="url"
                value={draft.webhookUrl ?? ''}
                placeholder="https://discord.com/api/webhooks/…"
                onChange={(e) =>
                  patch('webhookUrl', e.target.value.trim() === '' ? null : e.target.value)
                }
                style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.8rem' }}>
                {t('discord.webhook.urlHint')}
              </p>
            </div>

            <div style={{ marginTop: '0.75rem', display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void onTest()}
                disabled={!hasUrl || dirty || testState.kind === 'testing'}
                title={
                  !hasUrl
                    ? t('discord.test.disabledNoUrl')
                    : dirty
                    ? t('discord.test.disabledDirty')
                    : t('discord.test.tooltip')
                }
              >
                {testState.kind === 'testing' ? t('discord.test.sending') : t('discord.test.button')}
              </button>
              {testState.kind === 'done' && (
                <span
                  className={
                    testState.result.ok
                      ? 'form__msg form__msg--ok'
                      : 'form__msg form__msg--error'
                  }
                  style={{ margin: 0 }}
                >
                  {testState.result.message}
                </span>
              )}
            </div>
          </Section>

          <Section title={t('discord.identity.title')} hint={t('discord.identity.hint')}>
            <div className="form__field">
              <label>{t('discord.identity.username')}</label>
              <input
                type="text"
                value={draft.botUsername ?? ''}
                placeholder="Travian Bot"
                onChange={(e) =>
                  patch('botUsername', e.target.value === '' ? null : e.target.value)
                }
                disabled={!hasUrl}
                style={{ width: '100%', maxWidth: '20rem' }}
              />
            </div>
            <div className="form__field">
              <label>{t('discord.identity.avatarUrl')}</label>
              <input
                type="url"
                value={draft.botAvatarUrl ?? ''}
                placeholder="https://…/avatar.png"
                onChange={(e) =>
                  patch('botAvatarUrl', e.target.value === '' ? null : e.target.value)
                }
                disabled={!hasUrl}
                style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.8rem' }}>
                {t('discord.identity.avatarHint')}
              </p>
            </div>
          </Section>

          <Section title={t('discord.mentions.title')} hint={t('discord.mentions.hint')}>
            <div className="form__field">
              <label>{t('discord.mentions.userId')}</label>
              <input
                type="text"
                value={draft.mentionUserId ?? ''}
                placeholder="<@123456789012345678>"
                onChange={(e) =>
                  patch('mentionUserId', e.target.value === '' ? null : e.target.value)
                }
                disabled={!hasUrl}
                style={{ width: '100%', maxWidth: '24rem', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
            </div>
            <div className="form__field">
              <label>{t('discord.mentions.roleId')}</label>
              <input
                type="text"
                value={draft.mentionRoleId ?? ''}
                placeholder="<@&123456789012345678>"
                onChange={(e) =>
                  patch('mentionRoleId', e.target.value === '' ? null : e.target.value)
                }
                disabled={!hasUrl}
                style={{ width: '100%', maxWidth: '24rem', fontFamily: 'monospace', fontSize: '0.85em' }}
              />
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.8rem' }}>
                {t('discord.mentions.format')}
              </p>
            </div>
          </Section>

          <Section title={t('discord.triggers.title')} hint={t('discord.triggers.hint')}>
            <ToggleRow
              label={t('discord.triggers.incomingAttack')}
              description={t('discord.triggers.incomingAttackHint')}
              checked={draft.notifyIncomingAttack}
              disabled={!hasUrl}
              onChange={(v) => patch('notifyIncomingAttack', v)}
            />
            <ToggleRow
              label={t('discord.triggers.incomingRaid')}
              description={t('discord.triggers.incomingRaidHint')}
              checked={draft.notifyIncomingRaid}
              disabled={!hasUrl}
              onChange={(v) => patch('notifyIncomingRaid', v)}
            />
            <ToggleRow
              label={t('discord.triggers.returnCatch')}
              description={t('discord.triggers.returnCatchHint')}
              checked={draft.notifyReturnCatch}
              disabled={!hasUrl}
              onChange={(v) => patch('notifyReturnCatch', v)}
            />
            <ToggleRow
              label={t('discord.triggers.captcha')}
              description={t('discord.triggers.captchaHint')}
              checked={draft.notifyCaptcha}
              disabled={!hasUrl}
              onChange={(v) => patch('notifyCaptcha', v)}
            />
            <ToggleRow
              label={t('discord.triggers.sessionLost')}
              description={t('discord.triggers.sessionLostHint')}
              checked={draft.notifySessionLost}
              disabled={!hasUrl}
              onChange={(v) => patch('notifySessionLost', v)}
            />
            <ToggleRow
              label={t('discord.triggers.resourceOverflow')}
              description={t('discord.triggers.resourceOverflowHint')}
              checked={draft.notifyResourceOverflow}
              disabled={!hasUrl}
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

/**
 * Inline labelled-checkbox row used for each notification toggle. Pulled
 * out so the markup in the main JSX is one line per event instead of five.
 */
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
