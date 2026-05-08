import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { farmlistsApi } from '../api/farmlists';
import type { Farmlist, FarmlistType } from '../types/farmlist';

/**
 * Farmlists page — scoped to the current account.
 * <p>
 * Lists are discovered via the rally-point scraper (auto on login, or via the
 * "Rescan from rally point" button here), not created manually. What the user
 * edits here is *policy*: PVE vs PVP, how often the scheduler should fire,
 * and an optional group tag for bulk-send grouping.
 */
export default function FarmlistsListPage() {
  const { accountId } = useCurrentAccount();

  const [farmlists, setFarmlists] = useState<Farmlist[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [rescanBusy, setRescanBusy] = useState(false);
  const [sendAllBusy, setSendAllBusy] = useState(false);
  const [rowBusy, setRowBusy] = useState<Record<number, boolean>>({});
  const [savingId, setSavingId] = useState<number | null>(null);
  const [banner, setBanner] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const reload = useCallback(async () => {
    if (accountId == null) {
      setFarmlists([]);
      return;
    }
    setLoading(true);
    try {
      const data = await farmlistsApi.list(accountId);
      setFarmlists(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load farmlists');
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const groups = useMemo(() => {
    const set = new Set<string>();
    for (const fl of farmlists) if (fl.groupTag) set.add(fl.groupTag);
    return Array.from(set).sort();
  }, [farmlists]);

  const rescan = async () => {
    if (accountId == null) return;
    setRescanBusy(true);
    setBanner(null);
    try {
      const res = await farmlistsApi.scrape(accountId);
      setBanner({ kind: 'ok', text: `Rescan done — ${res.farmlistCount} list(s) discovered.` });
      await reload();
    } catch (err) {
      setBanner({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Rescan failed',
      });
    } finally {
      setRescanBusy(false);
    }
  };

  const sendAll = async () => {
    if (accountId == null) return;
    setSendAllBusy(true);
    setBanner(null);
    try {
      const res = await farmlistsApi.sendAllForAccount(accountId);
      setBanner({ kind: 'ok', text: `Sent ${res.listsSent} list(s).` });
      await reload();
    } catch (err) {
      setBanner({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Send-all failed',
      });
    } finally {
      setSendAllBusy(false);
    }
  };

  const sendOne = async (fl: Farmlist) => {
    setRowBusy((s) => ({ ...s, [fl.id]: true }));
    setBanner(null);
    try {
      const res = await farmlistsApi.sendAll(fl.id);
      setBanner({ kind: 'ok', text: `"${fl.name}" — ${res.targetsSent} target(s) submitted.` });
      await reload();
    } catch (err) {
      setBanner({
        kind: 'err',
        text: err instanceof Error ? err.message : `Send failed for "${fl.name}"`,
      });
    } finally {
      setRowBusy((s) => {
        const { [fl.id]: _, ...rest } = s;
        return rest;
      });
    }
  };

  /**
   * Persist a single farmlist's policy. We do this on blur/change rather
   * than with a Save button because each list has only three policy fields
   * and chunking them into a form would feel heavier than necessary.
   */
  const persistPolicy = async (
    fl: Farmlist,
    patch: Partial<Pick<Farmlist, 'type' | 'sendIntervalMinutes' | 'groupTag' | 'enabled'>>,
  ) => {
    const next: Farmlist = { ...fl, ...patch };
    // Optimistic update so the inputs feel responsive.
    setFarmlists((prev) => prev.map((x) => (x.id === fl.id ? next : x)));
    setSavingId(fl.id);
    try {
      await farmlistsApi.update(fl.id, {
        name: next.name,
        description: next.description,
        enabled: next.enabled,
        type: next.type,
        sendIntervalMinutes: next.sendIntervalMinutes,
        groupTag: next.groupTag,
        targets: next.targets,
      });
    } catch (err) {
      // Revert on failure so the UI doesn't lie.
      setFarmlists((prev) => prev.map((x) => (x.id === fl.id ? fl : x)));
      setBanner({
        kind: 'err',
        text: err instanceof Error ? err.message : `Save failed for "${fl.name}"`,
      });
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="stack">
      <div>
        <h1 className="page__title">Farmlists</h1>
        <p className="page__subtitle">
          Raid lists are scraped from the rally point automatically. Set each list's type,
          interval, and optional group here — the scheduler takes it from there.
        </p>
      </div>

      {accountId == null ? (
        <Card>
          <p className="muted">Pick an account on the Accounts page to see its farmlists.</p>
        </Card>
      ) : (
        <>
          <Card title="Actions">
            <div className="toolbar" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={rescan}
                disabled={rescanBusy}
              >
                {rescanBusy ? 'Rescanning…' : 'Rescan from rally point'}
              </button>
              <button
                type="button"
                className="btn btn--primary"
                onClick={sendAll}
                disabled={sendAllBusy || farmlists.length === 0}
              >
                {sendAllBusy ? 'Sending…' : 'Send all lists now'}
              </button>
              {banner && (
                <span
                  className={
                    banner.kind === 'ok'
                      ? 'form__msg form__msg--success'
                      : 'form__msg form__msg--error'
                  }
                  style={{ flex: 1 }}
                >
                  {banner.text}
                </span>
              )}
            </div>
          </Card>

          <Card title={`Farmlists (${farmlists.length})`}>
            {loading && <p className="muted">Loading…</p>}
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!loading && !error && farmlists.length === 0 && (
              <p className="muted">
                No farmlists yet. Click <em>Rescan from rally point</em> to discover them.
              </p>
            )}
            {!loading && !error && farmlists.length > 0 && (
              <table className="table">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Interval (min)</th>
                    <th>Group</th>
                    <th>Targets</th>
                    <th>Last sent</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {farmlists.map((fl) => (
                    <tr key={fl.id}>
                      <td>
                        <Link to={`/farmlists/${fl.id}`} className="link">{fl.name}</Link>
                      </td>
                      <td>
                        <select
                          value={fl.type}
                          onChange={(e) =>
                            void persistPolicy(fl, { type: e.target.value as FarmlistType })
                          }
                          disabled={savingId === fl.id}
                        >
                          <option value="PVE">PVE</option>
                          <option value="PVP">PVP</option>
                        </select>
                      </td>
                      <td>
                        <input
                          type="number"
                          min={1}
                          max={1440}
                          step={1}
                          value={fl.sendIntervalMinutes ?? ''}
                          placeholder="manual"
                          style={{ width: '5.5rem' }}
                          onChange={(e) => {
                            const raw = e.target.value.trim();
                            const next = raw === '' ? null : Number(raw);
                            if (next !== null && !Number.isFinite(next)) return;
                            setFarmlists((prev) =>
                              prev.map((x) =>
                                x.id === fl.id ? { ...x, sendIntervalMinutes: next } : x,
                              ),
                            );
                          }}
                          onBlur={(e) => {
                            const raw = e.target.value.trim();
                            const next = raw === '' ? null : Number(raw);
                            void persistPolicy(fl, { sendIntervalMinutes: next });
                          }}
                          disabled={savingId === fl.id}
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          list="farmlist-groups"
                          value={fl.groupTag ?? ''}
                          placeholder="—"
                          maxLength={60}
                          style={{ width: '8rem' }}
                          onChange={(e) => {
                            const v = e.target.value;
                            setFarmlists((prev) =>
                              prev.map((x) => (x.id === fl.id ? { ...x, groupTag: v } : x)),
                            );
                          }}
                          onBlur={(e) => {
                            const v = e.target.value.trim();
                            void persistPolicy(fl, { groupTag: v === '' ? null : v });
                          }}
                          disabled={savingId === fl.id}
                        />
                      </td>
                      <td>{fl.targets.length}</td>
                      <td className="muted">
                        {fl.lastSentAt ? new Date(fl.lastSentAt).toLocaleString() : '—'}
                      </td>
                      <td>
                        <label className="checkbox">
                          <input
                            type="checkbox"
                            checked={fl.enabled}
                            onChange={(e) => void persistPolicy(fl, { enabled: e.target.checked })}
                            disabled={savingId === fl.id}
                          />
                          {fl.enabled ? 'Enabled' : 'Disabled'}
                        </label>
                      </td>
                      <td style={{ display: 'flex', gap: '0.5rem' }}>
                        <button
                          type="button"
                          className="btn btn--ghost"
                          onClick={() => void sendOne(fl)}
                          disabled={
                            !fl.enabled || fl.travianListId == null || rowBusy[fl.id] === true
                          }
                          title={
                            fl.travianListId == null
                              ? 'Rescan the rally point first — list id unknown'
                              : 'Send this list now'
                          }
                        >
                          {rowBusy[fl.id] ? 'Sending…' : 'Send now'}
                        </button>
                        <Link to={`/farmlists/${fl.id}`} className="link">Open →</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {/* Shared datalist so the group input auto-suggests existing tags. */}
            <datalist id="farmlist-groups">
              {groups.map((g) => (
                <option key={g} value={g} />
              ))}
            </datalist>
          </Card>
        </>
      )}
    </div>
  );
}
