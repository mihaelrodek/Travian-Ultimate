import { useEffect, useState, type ReactNode } from 'react';
import Card from '../../components/Card';
import { accountSettingsApi } from '../../api/settings';
import {
  ALERT_CHANNELS,
  type AccountSettings,
  type AccountSettingsUpdate,
  type AlertChannel,
} from '../../types/settings';
import AccountProxiesPanel from '../accounts/AccountProxiesPanel';
import ScheduleEditor from './ScheduleEditor';

interface Props {
  accountId: number;
}

type Draft = Omit<AccountSettings, 'accountId' | 'proxyPasswordSet'>;
type LoadState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; message: string };
type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

/**
 * Per-account settings panel — proxies, schedule, attack alerts.
 * <p>
 * Rendered inline on the account detail page (no longer a standalone
 * /account-settings page). Three top-level collapsed sections, all closed
 * by default: <strong>Proxy setup</strong> (multi-proxy, handled by
 * {@link AccountProxiesPanel}), <strong>Active schedule</strong>, and
 * <strong>Additional options</strong> (attack alerts today; future misc
 * account toggles land here).
 * <p>
 * Legacy {@code proxyUrl}/{@code proxyUsername}/{@code proxyPassword} fields
 * on {@link AccountSettings} are preserved but hidden — the browser session
 * manager still reads them until the multi-proxy flow is wired through to
 * the Playwright launcher. Saving this panel round-trips them unchanged so
 * the hero/oasis/NPC refactors elsewhere don't zero them out.
 */
export default function AccountSettingsPanel({ accountId }: Props) {
  const [loadState, setLoadState] = useState<LoadState>({ kind: 'loading' });
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [draft, setDraft] = useState<Draft | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadState({ kind: 'loading' });
    accountSettingsApi
      .get(accountId)
      .then((s) => {
        if (cancelled) return;
        const { accountId: _id, proxyPasswordSet: _pwdSet, ...rest } = s;
        void _id;
        void _pwdSet;
        setDraft(rest);
        setLoadState({ kind: 'ready' });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : 'Failed to load settings';
        setLoadState({ kind: 'error', message });
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  if (loadState.kind === 'loading') return <Card><p className="muted">Loading settings…</p></Card>;
  if (loadState.kind === 'error') return <Card><p className="form__msg form__msg--error">{loadState.message}</p></Card>;
  if (!draft) return null;

  const patch = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const toggleChannel = (channel: AlertChannel) => {
    const current = new Set(draft.alertChannels);
    if (current.has(channel)) current.delete(channel);
    else current.add(channel);
    patch('alertChannels', Array.from(current));
  };

  const save = async () => {
    if (!draft) return;
    setSaveState({ kind: 'saving' });
    const body: AccountSettingsUpdate = {
      ...draft,
      // We no longer surface a proxy password input here — the multi-proxy
      // panel owns that. Send null to mean "leave the legacy account-level
      // proxy password alone" so renaming or toggling alerts doesn't clear
      // whatever the browser session manager is still reading.
      proxyPassword: null,
    };
    try {
      const saved = await accountSettingsApi.update(accountId, body);
      const { accountId: _id, proxyPasswordSet: _pwdSet, ...rest } = saved;
      void _id;
      void _pwdSet;
      setDraft(rest);
      setSaveState({ kind: 'saved' });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Save failed';
      setSaveState({ kind: 'error', message });
    }
  };

  return (
    <div className="stack">
      {/* ---------- Proxy setup ---------- */}
      <Card title="Proxy setup">
        <div className="settings-accordion">
          <SettingsGroup title="Proxies">
            <AccountProxiesPanel accountId={accountId} />
          </SettingsGroup>
        </div>
      </Card>

      {/* ---------- Active schedule (collapsible, no hint) ---------- */}
      <Card title="Active schedule">
        <div className="settings-accordion">
          <SettingsGroup title="Schedule">
            <ScheduleEditor
              value={draft.activeWindows}
              onChange={(v) => patch('activeWindows', v)}
            />
            <div className="settings-savebar">
              <div className="settings-savebar__msg">
                {saveState.kind === 'saving' && <span className="muted">Saving…</span>}
                {saveState.kind === 'saved' && <span className="pill pill--success">Saved</span>}
                {saveState.kind === 'error' && (
                  <span className="form__msg form__msg--error" style={{ margin: 0 }}>
                    {saveState.message}
                  </span>
                )}
              </div>
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={save}
                disabled={saveState.kind === 'saving'}
              >
                Save schedule
              </button>
            </div>
          </SettingsGroup>
        </div>
      </Card>

      {/* ---------- Additional options (collapsible) ---------- */}
      <Card title="Additional options">
        <div className="settings-accordion">
          <SettingsGroup title="Attack alerts">
            <div className="form form--compact">
              <label className="form__check form__field--full">
                <input
                  type="checkbox"
                  checked={draft.alertOnAttack}
                  onChange={(e) => patch('alertOnAttack', e.target.checked)}
                />
                <span>Notify on incoming attacks</span>
              </label>

              <label className="form__check form__field--full">
                <input
                  type="checkbox"
                  checked={draft.alertIncludeTroopCounts}
                  onChange={(e) => patch('alertIncludeTroopCounts', e.target.checked)}
                />
                <span>Include troop counts when scoutable</span>
              </label>

              <div className="form__field form__field--full">
                <label>Channels</label>
                <div className="form__checkgroup">
                  {ALERT_CHANNELS.map((ch) => (
                    <label key={ch} className="form__check">
                      <input
                        type="checkbox"
                        checked={draft.alertChannels.includes(ch)}
                        onChange={() => toggleChannel(ch)}
                      />
                      <span>{humanize(ch)}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="settings-savebar">
                <div className="settings-savebar__msg">
                  {saveState.kind === 'saving' && <span className="muted">Saving…</span>}
                  {saveState.kind === 'saved' && <span className="pill pill--success">Saved</span>}
                  {saveState.kind === 'error' && (
                    <span className="form__msg form__msg--error" style={{ margin: 0 }}>
                      {saveState.message}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  className="btn btn--primary btn--sm"
                  onClick={save}
                  disabled={saveState.kind === 'saving'}
                >
                  Save alerts
                </button>
              </div>
            </div>
          </SettingsGroup>
        </div>
      </Card>
    </div>
  );
}

/**
 * Collapsible section wrapper for the settings panel. Uses a native
 * {@code <details>} so keyboard + screen-reader behavior is correct without
 * hand-rolled state. The Schedule section stays outside this wrapper because
 * it's the most-edited card and users complained when it was hidden behind a
 * click.
 */
function SettingsGroup({
  title,
  hint,
  defaultOpen,
  children,
}: {
  title: string;
  hint?: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details className="settings-group" open={defaultOpen}>
      <summary className="settings-group__summary">
        <span className="settings-group__title">{title}</span>
        {hint && <span className="settings-group__hint muted">{hint}</span>}
      </summary>
      <div className="settings-group__body">{children}</div>
    </details>
  );
}

function humanize(value: string): string {
  return value
    .split('_')
    .map((s) => s.charAt(0) + s.slice(1).toLowerCase())
    .join(' ');
}
