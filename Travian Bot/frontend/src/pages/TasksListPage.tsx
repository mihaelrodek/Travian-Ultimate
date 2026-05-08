import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { tasksApi } from '../api/tasks';
import { TASK_STATUSES, TASK_TYPES, type BotTask, type TaskStatus } from '../types/task';

/**
 * Tasks for the currently-scoped account. Tasks are always per-account (you
 * build a queue of work for *this* bot instance) so there is no cross-account
 * view and the "Account" selector on the create form is gone.
 */
export default function TasksListPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [tasks, setTasks] = useState<BotTask[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);
  const [filterStatus, setFilterStatus] = useState<TaskStatus | 'all'>('all');

  // Inline create
  const [draftType, setDraftType] = useState<string>('CUSTOM');
  const [draftDescription, setDraftDescription] = useState('');
  const [draftScheduledAt, setDraftScheduledAt] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const reload = async () => {
    if (accountId == null) {
      setTasks([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const data = await tasksApi.list(
        accountId,
        filterStatus === 'all' ? undefined : filterStatus,
      );
      setTasks(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tasks');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, filterStatus]);

  const onCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (accountId == null) {
      setCreateError('No account selected');
      return;
    }
    if (!draftType) {
      setCreateError('Type is required');
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      await tasksApi.create({
        accountId,
        type: draftType,
        description: draftDescription.trim() || null,
        scheduledAt: draftScheduledAt ? new Date(draftScheduledAt).toISOString() : null,
      });
      setDraftDescription('');
      setDraftScheduledAt('');
      await reload();
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Failed to create task');
    } finally {
      setCreating(false);
    }
  };

  const onStatusChange = async (task: BotTask, next: TaskStatus) => {
    try {
      await tasksApi.update(task.id, {
        type: task.type,
        description: task.description,
        scheduledAt: task.scheduledAt,
        status: next,
      });
      await reload();
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Status update failed');
    }
  };

  const onDelete = async (task: BotTask) => {
    if (!confirm(`Delete task #${task.id}?`)) return;
    try {
      await tasksApi.delete(task.id);
      await reload();
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Delete failed');
    }
  };

  return (
    <div className="stack">
      <div>
        <h1 className="page__title">Tasks</h1>
        <p className="page__subtitle">Queue of work for the bot on the active account.</p>
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to see its tasks.
          </p>
        </Card>
      )}

      {accountId != null && (
        <>
          <Card title="New task">
            <form className="form form__grid" onSubmit={onCreate}>
              <label className="form__field">
                Type
                <select value={draftType} onChange={(e) => setDraftType(e.target.value)} required>
                  {TASK_TYPES.map((t) => (
                    <option key={t} value={t}>{humanize(t)}</option>
                  ))}
                </select>
              </label>
              <label className="form__field">
                Scheduled at (optional)
                <input
                  type="datetime-local"
                  value={draftScheduledAt}
                  onChange={(e) => setDraftScheduledAt(e.target.value)}
                />
              </label>
              <label className="form__field" style={{ gridColumn: '1 / -1' }}>
                Description
                <input
                  type="text"
                  value={draftDescription}
                  onChange={(e) => setDraftDescription(e.target.value)}
                  placeholder="e.g. Run farmlist 'West-Oasen' at 19:00"
                />
              </label>
              <div style={{ gridColumn: '1 / -1', display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
                <button type="submit" className="btn btn--primary" disabled={creating}>
                  {creating ? 'Adding…' : '+ Add task'}
                </button>
                {createError && <span className="form__msg form__msg--error" style={{ flex: 1 }}>{createError}</span>}
              </div>
            </form>
          </Card>

          <Card title="Tasks">
            <div className="toolbar">
              <label className="toolbar__label">Status</label>
              <select
                value={filterStatus}
                onChange={(e) => setFilterStatus(e.target.value as TaskStatus | 'all')}
              >
                <option value="all">All</option>
                {TASK_STATUSES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </div>

            {loading && <p className="muted">Loading…</p>}
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!loading && !error && tasks.length === 0 && <p className="muted">No tasks yet.</p>}
            {!loading && !error && tasks.length > 0 && (
              <table className="table">
                <thead>
                  <tr>
                    <th style={{ width: '4rem' }}>#</th>
                    <th>Type</th>
                    <th>Description</th>
                    <th style={{ width: '11rem' }}>Scheduled</th>
                    <th style={{ width: '8rem' }}>Status</th>
                    <th style={{ width: '14rem' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.map((t) => (
                    <tr key={t.id}>
                      <td className="muted">#{t.id}</td>
                      <td><code className="code-chip">{t.type}</code></td>
                      <td>
                        {t.description ?? <span className="muted">—</span>}
                        {t.resultMessage && (
                          <div className="muted" style={{ fontSize: '0.8rem', marginTop: '0.2rem' }}>
                            → {t.resultMessage}
                          </div>
                        )}
                      </td>
                      <td className="muted">{t.scheduledAt ? new Date(t.scheduledAt).toLocaleString() : '—'}</td>
                      <td><span className={`pill pill--${statusPill(t.status)}`}>{t.status}</span></td>
                      <td>
                        <div style={{ display: 'flex', gap: '0.25rem' }}>
                          <select
                            value={t.status}
                            onChange={(e) => onStatusChange(t, e.target.value as TaskStatus)}
                          >
                            {TASK_STATUSES.map((s) => (
                              <option key={s} value={s}>{s}</option>
                            ))}
                          </select>
                          <button type="button" className="btn btn--sm btn--danger" onClick={() => onDelete(t)}>✕</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </div>
  );
}

function humanize(key: string): string {
  return key.toLowerCase().split('_').map((s) => s.charAt(0).toUpperCase() + s.slice(1)).join(' ');
}

function statusPill(status: TaskStatus): 'success' | 'danger' | 'neutral' {
  if (status === 'DONE') return 'success';
  if (status === 'FAILED') return 'danger';
  return 'neutral';
}
