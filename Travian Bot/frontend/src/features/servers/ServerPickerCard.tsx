import { useEffect, useMemo, useState } from 'react';
import Card from '../../components/Card';
import { useAccounts } from '../accounts/AccountsContext';
import { useCurrentServer } from '../../hooks/useCurrentServer';
import { serversApi } from '../../api/servers';
import type { Server, UpcomingServer } from '../../types/server';

/**
 * Controlled server picker — dropdown + inline edit form + re-seed / delete
 * helpers. Used at the top of the intel / cropper pages so the user can swap
 * which world they're searching.
 *
 * The picker is controlled (`serverId` + `onChange`) so a page can choose to
 * fork its own server-id state if needed, but it reads the list and the
 * refresh handle from {@link useCurrentServer} directly.
 *
 * Empty-state hint: when the API returns zero servers but local accounts
 * exist, we surface a one-click "Re-seed from accounts" button so the user
 * can bootstrap the table from their existing account URLs.
 */

type Props = {
  /** Selected server id (controlled). */
  serverId: number | null;
  /** Callback when the user changes selection. */
  onChange: (id: number | null) => void;
};

type FormMode = 'create' | 'edit' | null;

interface FormState {
  url: string;
  name: string;
  region: string;
  gameWorld: string;
  active: boolean;
}

const emptyForm: FormState = { url: '', name: '', region: '', gameWorld: '', active: true };

function fromServer(s: Server): FormState {
  return {
    url: s.url,
    name: s.name,
    region: s.region ?? '',
    gameWorld: s.gameWorld ?? '',
    active: s.active,
  };
}

function fmtRelative(iso: string | null): string {
  if (!iso) return 'never';
  try {
    const then = new Date(iso).getTime();
    const now = Date.now();
    const diff = Math.max(0, now - then);
    const mins = Math.floor(diff / 60_000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ago`;
    const days = Math.floor(hrs / 24);
    if (days < 30) return `${days}d ago`;
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

export default function ServerPickerCard({ serverId, onChange }: Props) {
  const { servers, loading, error, refresh } = useCurrentServer();
  const { accounts } = useAccounts();

  const [mode, setMode] = useState<FormMode>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [busy, setBusy] = useState<boolean>(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [seedBusy, setSeedBusy] = useState<boolean>(false);
  const [confirmDelete, setConfirmDelete] = useState<boolean>(false);
  // Discovery + upcoming-launches state. All TODO i18n — strings hardcoded English.
  const [discoverBusy, setDiscoverBusy] = useState<boolean>(false);
  const [upcoming, setUpcoming] = useState<UpcomingServer[] | null>(null);
  const [upcomingBusy, setUpcomingBusy] = useState<boolean>(false);
  const [upcomingError, setUpcomingError] = useState<string | null>(null);
  const [calendarBusy, setCalendarBusy] = useState<boolean>(false);
  // Force the upcoming block visible after the user has interacted with it,
  // even if the list comes back empty — they explicitly asked for it.
  const [upcomingExpanded, setUpcomingExpanded] = useState<boolean>(false);

  const selected = useMemo(
    () => (serverId != null ? servers.find((s) => s.id === serverId) ?? null : null),
    [serverId, servers],
  );

  // If the currently picked server vanished (deleted by another tab), clear
  // it so the page falls back to the empty state instead of trying to query
  // a stale id.
  useEffect(() => {
    if (serverId != null && !loading && servers.length > 0 && !servers.find((s) => s.id === serverId)) {
      onChange(null);
    }
  }, [serverId, servers, loading, onChange]);

  const openCreate = () => {
    setForm(emptyForm);
    setMode('create');
    setMsg(null);
  };

  const openEdit = () => {
    if (!selected) return;
    setForm(fromServer(selected));
    setMode('edit');
    setMsg(null);
  };

  const cancelForm = () => {
    setMode(null);
    setForm(emptyForm);
  };

  const saveForm = async () => {
    setBusy(true);
    setMsg(null);
    try {
      if (mode === 'create') {
        const created = await serversApi.create({
          url: form.url.trim(),
          name: form.name.trim(),
          region: form.region.trim() || undefined,
          gameWorld: form.gameWorld.trim() || undefined,
        });
        refresh();
        onChange(created.id);
        setMsg({ kind: 'ok', text: `Added "${created.name}".` });
        setMode(null);
      } else if (mode === 'edit' && selected) {
        await serversApi.update(selected.id, {
          name: form.name.trim(),
          region: form.region.trim() || undefined,
          gameWorld: form.gameWorld.trim() || undefined,
          active: form.active,
        });
        refresh();
        setMsg({ kind: 'ok', text: 'Saved.' });
        setMode(null);
      }
    } catch (err) {
      setMsg({ kind: 'err', text: err instanceof Error ? err.message : 'Save failed' });
    } finally {
      setBusy(false);
    }
  };

  const seed = async () => {
    setSeedBusy(true);
    setMsg(null);
    try {
      const resp = await serversApi.seedFromAccounts();
      refresh();
      setMsg({
        kind: 'ok',
        text:
          resp.created > 0
            ? `Imported ${resp.created} server(s) from your accounts.`
            : 'No new servers found — every account URL already has a server row.',
      });
    } catch (err) {
      setMsg({ kind: 'err', text: err instanceof Error ? err.message : 'Seed failed' });
    } finally {
      setSeedBusy(false);
    }
  };

  // TODO i18n — strings below are hardcoded English.
  const discover = async () => {
    setDiscoverBusy(true);
    setMsg(null);
    try {
      const resp = await serversApi.discoverAll();
      refresh();
      const tail = resp.alreadyKnown > 0
        ? ` ${resp.alreadyKnown} already known.`
        : '';
      setMsg({
        kind: 'ok',
        text: resp.discovered > 0
          ? `Found ${resp.discovered} new server(s) — added as inactive. Enable in the dropdown.${tail}`
          : `No new servers found.${tail}`,
      });
    } catch (err) {
      setMsg({ kind: 'err', text: err instanceof Error ? err.message : 'Discovery failed' });
    } finally {
      setDiscoverBusy(false);
    }
  };

  const loadUpcoming = async () => {
    setUpcomingBusy(true);
    setUpcomingError(null);
    try {
      const list = await serversApi.listUpcoming();
      setUpcoming(list);
    } catch (err) {
      setUpcomingError(err instanceof Error ? err.message : 'Failed to load upcoming launches');
    } finally {
      setUpcomingBusy(false);
    }
  };

  const refreshCalendar = async () => {
    setCalendarBusy(true);
    setUpcomingError(null);
    try {
      const report = await serversApi.refreshCalendar();
      // Refresh the list so the user sees the just-scraped rows.
      await loadUpcoming();
      setUpcomingExpanded(true);
      setMsg({
        kind: 'ok',
        text: `Calendar refreshed — parsed ${report.parsed}, ${report.created} new, ${report.updated} updated.`,
      });
    } catch (err) {
      setUpcomingError(err instanceof Error ? err.message : 'Calendar refresh failed');
    } finally {
      setCalendarBusy(false);
    }
  };

  // Lazy-load the upcoming list the first time the user expands the block.
  // Keeps the picker quiet for users who don't engage with this feature.
  useEffect(() => {
    if (upcomingExpanded && upcoming === null) {
      void loadUpcoming();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upcomingExpanded]);

  const deleteServer = async () => {
    if (!selected) return;
    setBusy(true);
    setMsg(null);
    try {
      await serversApi.remove(selected.id);
      onChange(null);
      refresh();
      setConfirmDelete(false);
      setMsg({ kind: 'ok', text: `Deleted "${selected.name}".` });
    } catch (err) {
      setMsg({ kind: 'err', text: err instanceof Error ? err.message : 'Delete failed' });
    } finally {
      setBusy(false);
    }
  };

  const noServers = !loading && servers.length === 0;
  const hasAccounts = accounts.length > 0;
  const canDelete = selected != null && selected.accountCount === 0;

  return (
    // TODO i18n — strings in this component are hardcoded English.
    <Card title="Travian server" hint="Picks which world the intel / cropper search runs against.">
      {error && <p className="form__msg form__msg--error">{error}</p>}

      {noServers && hasAccounts && (
        <div className="scope-banner" style={{ marginBottom: '0.75rem' }}>
          <span className="scope-banner__label">
            No servers yet — click <strong>Re-seed from accounts</strong> to import the
            ones your accounts use.
          </span>
        </div>
      )}

      <div
        className="toolbar"
        style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 0 }}
      >
        <label className="form__field" style={{ flex: '1 1 22rem', minWidth: '14rem' }}>
          <span>Server</span>
          <select
            value={serverId == null ? '' : String(serverId)}
            onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
            disabled={loading || servers.length === 0}
          >
            <option value="">{loading ? 'Loading…' : '— pick a server —'}</option>
            {servers.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name} — {s.url}
                {s.active ? '' : ' (inactive)'}
              </option>
            ))}
          </select>
          {selected && (
            <small className="muted" style={{ fontSize: '0.75rem', marginTop: '0.25rem' }}>
              {selected.accountCount} account{selected.accountCount === 1 ? '' : 's'}
              {selected.lastMapSyncPlayerCount != null
                ? ` · ${selected.lastMapSyncPlayerCount.toLocaleString()} players`
                : ''}
              {' · synced '}{fmtRelative(selected.lastMapSyncedAt)}
              {selected.region ? ` · ${selected.region}` : ''}
              {selected.gameWorld ? ` · ${selected.gameWorld}` : ''}
            </small>
          )}
        </label>

        <div className="toolbar" style={{ gap: '0.4rem', marginBottom: 0, flexWrap: 'wrap' }}>
          <button
            type="button"
            className={noServers && hasAccounts ? 'btn btn--primary' : 'btn btn--ghost'}
            onClick={() => void seed()}
            disabled={seedBusy}
            title="Walk your existing accounts and create a server row for every URL we don't have yet"
          >
            {seedBusy ? 'Seeding…' : 'Re-seed from accounts'}
          </button>
          {/* TODO i18n */}
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => void discover()}
            disabled={discoverBusy}
            title="Probe ~600 candidate URLs across communities/speeds. Takes 30-60s. New servers are added as inactive."
          >
            {discoverBusy ? 'Probing ~600 URLs, 30-60s…' : 'Discover servers'}
          </button>
          <button type="button" className="btn btn--ghost" onClick={openCreate} disabled={mode !== null}>
            Add new server
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={openEdit}
            disabled={!selected || mode !== null}
            title={selected ? 'Edit this server' : 'Pick a server first'}
          >
            Edit
          </button>
          {canDelete && (
            <button
              type="button"
              className="btn btn--danger"
              onClick={() => setConfirmDelete(true)}
              disabled={busy || mode !== null}
              title="Only servers with zero accounts can be deleted"
            >
              Delete
            </button>
          )}
        </div>
      </div>

      {msg && (
        <p
          className={
            msg.kind === 'ok' ? 'form__msg form__msg--success' : 'form__msg form__msg--error'
          }
          style={{ marginTop: '0.75rem' }}
        >
          {msg.text}
        </p>
      )}

      {confirmDelete && selected && (
        <div
          className="scope-banner scope-banner--warn"
          style={{ marginTop: '0.75rem', flexWrap: 'wrap' }}
        >
          <span className="scope-banner__label">
            Delete <strong>{selected.name}</strong>? This drops the map dataset for this
            server. No accounts will be affected (0 accounts use it).
          </span>
          <div className="toolbar" style={{ gap: '0.4rem', marginBottom: 0 }}>
            <button type="button" className="btn btn--danger" onClick={() => void deleteServer()} disabled={busy}>
              {busy ? 'Deleting…' : 'Yes, delete'}
            </button>
            <button type="button" className="btn btn--ghost" onClick={() => setConfirmDelete(false)} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {mode !== null && (
        <div
          style={{
            marginTop: '0.75rem',
            padding: '0.75rem',
            background: 'var(--surface-muted)',
            border: '1px solid var(--border)',
            borderRadius: 8,
          }}
        >
          <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>
            {mode === 'create' ? 'Add new server' : `Edit ${selected?.name ?? ''}`}
          </h3>
          <div
            className="form-grid"
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(14rem, 1fr))',
              gap: '0.6rem',
            }}
          >
            <label className="form__field">
              <span>Name</span>
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="e.g. ts7 EU"
                autoFocus
              />
            </label>
            <label className="form__field">
              <span>URL</span>
              <input
                type="text"
                value={form.url}
                onChange={(e) => setForm({ ...form, url: e.target.value })}
                placeholder="https://ts7.x1.europe.travian.com/"
                disabled={mode === 'edit'}
                title={mode === 'edit' ? 'URL is immutable — recreate the server to change it' : undefined}
              />
            </label>
            <label className="form__field">
              <span>Region (optional)</span>
              <input
                type="text"
                value={form.region}
                onChange={(e) => setForm({ ...form, region: e.target.value })}
                placeholder="e.g. Europe"
              />
            </label>
            <label className="form__field">
              <span>Game world (optional)</span>
              <input
                type="text"
                value={form.gameWorld}
                onChange={(e) => setForm({ ...form, gameWorld: e.target.value })}
                placeholder="e.g. x1 / x3 / Legends"
              />
            </label>
            {mode === 'edit' && (
              <label className="form__field" style={{ alignSelf: 'end' }}>
                <span>&nbsp;</span>
                <label className="form__check">
                  <input
                    type="checkbox"
                    checked={form.active}
                    onChange={(e) => setForm({ ...form, active: e.target.checked })}
                  />
                  Active
                </label>
              </label>
            )}
          </div>
          <div className="toolbar" style={{ gap: '0.5rem', marginTop: '0.75rem', marginBottom: 0 }}>
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => void saveForm()}
              disabled={busy || !form.name.trim() || (mode === 'create' && !form.url.trim())}
            >
              {busy ? 'Saving…' : 'Save'}
            </button>
            <button type="button" className="btn btn--ghost" onClick={cancelForm} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Upcoming launches — collapsed by default. Hidden entirely until the
          user expands it the first time, OR if they expand it and the list
          really is empty AND they haven't clicked refresh. TODO i18n. */}
      {(upcomingExpanded || (upcoming != null && upcoming.length > 0)) && (
        <details
          open={upcomingExpanded}
          onToggle={(e) => setUpcomingExpanded((e.currentTarget as HTMLDetailsElement).open)}
          style={{ marginTop: '0.75rem' }}
        >
          <summary style={{ cursor: 'pointer', userSelect: 'none' }}>
            Upcoming launches
            {upcoming != null ? ` (${upcoming.length})` : ''}
          </summary>
          <div style={{ marginTop: '0.5rem' }}>
            <div className="toolbar" style={{ gap: '0.4rem', marginBottom: '0.5rem' }}>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => void refreshCalendar()}
                disabled={calendarBusy}
                title="Re-scrape https://www.travian.com/international/calendar (Playwright)"
              >
                {calendarBusy ? 'Scraping calendar…' : 'Refresh calendar'}
              </button>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => void loadUpcoming()}
                disabled={upcomingBusy}
                title="Reload the upcoming-launches list from the database"
              >
                {upcomingBusy ? 'Loading…' : 'Reload list'}
              </button>
            </div>
            {upcomingError && (
              <p className="form__msg form__msg--error">{upcomingError}</p>
            )}
            {upcoming == null && !upcomingBusy && (
              <p className="muted" style={{ fontSize: '0.85rem' }}>Click Reload list to fetch upcoming launches.</p>
            )}
            {upcoming != null && upcoming.length === 0 && (
              <p className="muted" style={{ fontSize: '0.85rem' }}>
                No upcoming launches stored yet. Click <strong>Refresh calendar</strong> to populate from
                travian.com.
              </p>
            )}
            {upcoming != null && upcoming.length > 0 && (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                  <thead>
                    <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--border)' }}>
                      <th style={{ padding: '0.35rem 0.5rem' }}>World</th>
                      <th style={{ padding: '0.35rem 0.5rem' }}>Region</th>
                      <th style={{ padding: '0.35rem 0.5rem' }}>Speed</th>
                      <th style={{ padding: '0.35rem 0.5rem' }}>Starts</th>
                      <th style={{ padding: '0.35rem 0.5rem' }}>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {upcoming.map((u) => (
                      <tr key={u.id} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={{ padding: '0.35rem 0.5rem' }}>
                          {u.sourceUrl ? (
                            <a href={u.sourceUrl} target="_blank" rel="noreferrer">
                              {u.worldName}
                            </a>
                          ) : (
                            u.worldName
                          )}
                        </td>
                        <td style={{ padding: '0.35rem 0.5rem' }}>{u.region}</td>
                        <td style={{ padding: '0.35rem 0.5rem' }}>
                          {u.speed != null ? `x${u.speed}` : '—'}
                        </td>
                        <td style={{ padding: '0.35rem 0.5rem' }}>
                          {u.scheduledStartAt
                            ? new Date(u.scheduledStartAt).toLocaleDateString()
                            : 'TBD'}
                        </td>
                        <td style={{ padding: '0.35rem 0.5rem' }}>{u.status}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </details>
      )}

      {/* If the user hasn't expanded the upcoming block yet AND we haven't
          fetched anything, surface a tiny opener link so the feature is
          discoverable without cluttering the picker. TODO i18n. */}
      {!upcomingExpanded && upcoming == null && (
        <p style={{ marginTop: '0.5rem', fontSize: '0.8rem' }}>
          <button
            type="button"
            className="btn btn--ghost"
            style={{ padding: '0.25rem 0.5rem', fontSize: '0.8rem' }}
            onClick={() => setUpcomingExpanded(true)}
          >
            Show upcoming launches
          </button>
        </p>
      )}
    </Card>
  );
}
