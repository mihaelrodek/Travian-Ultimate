import type { FarmlistTarget } from '../../types/farmlist';

/**
 * Edits the target rows of a farmlist. Each target is a coordinate + named
 * troop payload (JSON blob — kept as a string so the user can paste
 * {"t1":50,"t4":2} directly and we don't have to model every tribe's troop set
 * in the frontend right now). Validation is light — the server checks the JSON.
 */
interface Props {
  targets: FarmlistTarget[];
  onChange: (targets: FarmlistTarget[]) => void;
}

export default function FarmlistTargetsEditor({ targets, onChange }: Props) {
  const patch = (index: number, changes: Partial<FarmlistTarget>) => {
    onChange(targets.map((t, i) => (i === index ? { ...t, ...changes } : t)));
  };

  const addRow = () => {
    const row: FarmlistTarget = {
      id: null,
      travianTargetId: null,
      name: null,
      coordinateX: 0,
      coordinateY: 0,
      troopsJson: '{}',
      enabled: true,
      lastSentAt: null,
    };
    onChange([...targets, row]);
  };

  const remove = (index: number) => {
    onChange(targets.filter((_, i) => i !== index));
  };

  return (
    <div>
      {targets.length === 0 ? (
        <p className="muted">No targets yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th style={{ width: '3rem' }}>#</th>
              <th>Name</th>
              <th style={{ width: '6rem' }}>X</th>
              <th style={{ width: '6rem' }}>Y</th>
              <th>Troops (JSON)</th>
              <th style={{ width: '5rem' }}>On</th>
              <th style={{ width: '11rem' }}>Last sent</th>
              <th style={{ width: '4rem' }} />
            </tr>
          </thead>
          <tbody>
            {targets.map((t, i) => (
              <tr key={t.id ?? `new-${i}`}>
                <td className="muted">{i + 1}</td>
                <td>
                  <input
                    type="text"
                    value={t.name ?? ''}
                    placeholder="(optional)"
                    onChange={(e) => patch(i, { name: e.target.value || null })}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    value={t.coordinateX}
                    onChange={(e) => patch(i, { coordinateX: Number(e.target.value) })}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    value={t.coordinateY}
                    onChange={(e) => patch(i, { coordinateY: Number(e.target.value) })}
                  />
                </td>
                <td>
                  <input
                    type="text"
                    className="input--mono"
                    value={t.troopsJson}
                    onChange={(e) => patch(i, { troopsJson: e.target.value })}
                    placeholder='{"t1":50,"t4":2}'
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={t.enabled}
                    onChange={(e) => patch(i, { enabled: e.target.checked })}
                  />
                </td>
                <td className="muted">{t.lastSentAt ? new Date(t.lastSentAt).toLocaleString() : '—'}</td>
                <td>
                  <button type="button" className="btn btn--sm btn--danger" onClick={() => remove(i)}>✕</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div style={{ marginTop: '0.75rem' }}>
        <button type="button" className="btn btn--ghost" onClick={addRow}>+ Add target</button>
      </div>
    </div>
  );
}
