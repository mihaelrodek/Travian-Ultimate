import { useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { incomingApi } from '../api/incoming';

/**
 * Defense → Report incoming attacks (manual paste-in flow).
 *
 * The user opens their rally-point Overview tab in Travian (Ctrl+A → Ctrl+C),
 * pastes into the textarea here, clicks Import. Backend parser handles both
 * HTML (view-source paste) and plain-text (rendered-page paste) and dedups
 * against rows already scraped automatically.
 *
 * Per-account scoped because incoming attacks are always against ONE of OUR
 * villages — we need the current account to know which.
 *
 * TODO i18n — strings hardcoded English.
 */
export default function ReportIncomingAttacksPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ kind: 'ok' | 'err'; message: string } | null>(null);

  const onImport = async () => {
    if (accountId == null || !text.trim()) return;
    setBusy(true);
    setResult(null);
    try {
      const resp = await incomingApi.importText(accountId, text);
      setResult({
        kind: 'ok',
        message: resp.imported > 0
          ? `Imported ${resp.imported} new attack${resp.imported === 1 ? '' : 's'}. Open the Incoming Attacks page to review.`
          : 'No new attacks were extracted (either the paste was empty, the format was unrecognised, or these are duplicates of rows we already know).',
      });
      setText('');
    } catch (err) {
      setResult({
        kind: 'err',
        message: err instanceof Error ? err.message : 'Import failed',
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Prijavi napad"
        subtitle="Paste-in iz rally pointa."
      />
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account on the <Link to="/accounts" className="link">Accounts page</Link> first —
            incoming attacks are scoped per account.
          </p>
        </Card>
      )}

      {accountId != null && (
        <>
          <Card title="How to import" hint="The bot will parse what you paste and add any waves it doesn't already have.">
            <ol style={{ margin: 0, paddingLeft: '1.25rem', lineHeight: 1.7 }}>
              <li>In Travian, open your <strong>rally point → Overview</strong> tab.</li>
              <li>Press <kbd>Ctrl</kbd>+<kbd>A</kbd> to select the page, then <kbd>Ctrl</kbd>+<kbd>C</kbd> to copy.</li>
              <li>Click into the box below and press <kbd>Ctrl</kbd>+<kbd>V</kbd>.</li>
              <li>Hit <strong>Import</strong>. Duplicates are skipped — safe to re-paste.</li>
            </ol>
          </Card>

          <Card title="Paste here">
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Paste from the rally-point Overview tab (Ctrl+V)…"
              rows={14}
              style={{
                width: '100%',
                fontFamily: 'monospace',
                fontSize: '0.85rem',
                padding: '0.75rem',
                borderRadius: 4,
                border: '1px solid var(--color-border, #ccc)',
                background: 'var(--color-surface, #fff)',
              }}
            />

            <div className="toolbar" style={{ gap: '0.5rem', marginTop: '0.75rem', flexWrap: 'wrap' }}>
              <button
                type="button"
                className="btn btn--primary"
                onClick={() => void onImport()}
                disabled={busy || !text.trim()}
              >
                {busy ? 'Importing…' : 'Import'}
              </button>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => { setText(''); setResult(null); }}
                disabled={busy || !text}
              >
                Clear
              </button>
              <Link to="/incoming" className="btn btn--ghost">
                Go to Incoming Attacks →
              </Link>
            </div>

            {result && (
              <p
                className={result.kind === 'ok' ? 'form__msg form__msg--success' : 'form__msg form__msg--error'}
                style={{ marginTop: '0.75rem' }}
              >
                {result.message}
              </p>
            )}
          </Card>

          <Card muted>
            <p className="muted" style={{ margin: 0 }}>
              <strong>Coming soon:</strong> a Chrome extension will let you push attacks straight
              from the rally point into the bot with one click — no copy-paste needed. See
              <code> ROADMAP.xlsx → EXT1 </code> in the project repo.
            </p>
          </Card>
        </>
      )}
    </div>
  );
}
