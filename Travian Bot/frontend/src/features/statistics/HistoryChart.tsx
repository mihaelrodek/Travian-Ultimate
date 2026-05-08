import type { HistoryPoint } from '../../types/statistics';

/**
 * Zero-dependency SVG line chart. We render inline instead of pulling in
 * Recharts because every extra dep pays ongoing maintenance cost, and the
 * chart only needs two axes and a single path — a ~80-line component is
 * cheaper than a library pull here.
 *
 * <p>Plots {@link HistoryPoint.score} against {@link HistoryPoint.capturedAt}.
 * The metric picker is gone — each stats tab has its own single metric
 * (PvP attack pts, PvE attack pts, defense pts, raided resources), and
 * offering a picker that always reads the same field would be noise.
 *
 * <p>If there are fewer than 2 points we render a flat helper message — a
 * chart with one point is just a dot and more confusing than no chart.
 */

interface HistoryChartProps {
  points: HistoryPoint[];
  /** Label shown above the chart (e.g. "Attack points"). Purely cosmetic. */
  yLabel?: string;
  /** Formatter for the Y axis + tooltip. Defaults to locale-formatted integers. */
  formatValue?: (n: number) => string;
  /** Shown in the empty state instead of the default "waiting for captures" text. */
  emptyText?: string;
}

const PAD = { top: 16, right: 16, bottom: 28, left: 64 };
const WIDTH = 720;
const HEIGHT = 260;

export default function HistoryChart({ points, yLabel, formatValue, emptyText }: HistoryChartProps) {
  const series = points
    .filter((p) => p.score != null)
    .map((p) => ({ t: p.capturedAt, y: p.score as number }));

  if (series.length < 2) {
    return (
      <div className="chart chart--empty muted" style={{ padding: '1.5rem', textAlign: 'center' }}>
        {emptyText ?? 'Not enough data yet — waiting for at least two captures.'}
      </div>
    );
  }

  const xs = series.map((p) => new Date(p.t).getTime());
  const ys = series.map((p) => p.y);
  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);
  const yMinRaw = Math.min(...ys);
  const yMaxRaw = Math.max(...ys);
  const yPad = Math.max(1, (yMaxRaw - yMinRaw) * 0.05);
  const yMin = yMinRaw - yPad;
  const yMax = yMaxRaw + yPad;

  const innerW = WIDTH - PAD.left - PAD.right;
  const innerH = HEIGHT - PAD.top - PAD.bottom;
  const xScale = (t: number) => PAD.left + ((t - xMin) / Math.max(1, xMax - xMin)) * innerW;
  const yScale = (v: number) => PAD.top + ((yMax - v) / Math.max(1, yMax - yMin)) * innerH;

  const path = series
    .map((p, i) => {
      const x = xScale(new Date(p.t).getTime());
      const y = yScale(p.y);
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');

  const fmt = formatValue ?? ((n: number) => n.toLocaleString());
  const yTicks = buildTicks(yMinRaw, yMaxRaw, 4);
  const xTicks = buildTicks(xMin, xMax, 5);

  return (
    <svg
      className="chart chart--svg"
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      width="100%"
      preserveAspectRatio="none"
      role="img"
      aria-label={yLabel ? `${yLabel} history chart` : 'Statistics history chart'}
    >
      {/* Axes */}
      <line
        x1={PAD.left} y1={PAD.top}
        x2={PAD.left} y2={HEIGHT - PAD.bottom}
        stroke="currentColor" strokeOpacity={0.25}
      />
      <line
        x1={PAD.left} y1={HEIGHT - PAD.bottom}
        x2={WIDTH - PAD.right} y2={HEIGHT - PAD.bottom}
        stroke="currentColor" strokeOpacity={0.25}
      />

      {/* Y gridlines + labels */}
      {yTicks.map((v) => {
        const y = yScale(v);
        return (
          <g key={`y-${v}`}>
            <line
              x1={PAD.left} y1={y}
              x2={WIDTH - PAD.right} y2={y}
              stroke="currentColor" strokeOpacity={0.08}
            />
            <text
              x={PAD.left - 6} y={y + 3}
              fontSize="10" textAnchor="end"
              fill="currentColor" fillOpacity={0.7}
            >
              {fmt(v)}
            </text>
          </g>
        );
      })}

      {/* X labels */}
      {xTicks.map((t) => {
        const x = xScale(t);
        return (
          <text
            key={`x-${t}`}
            x={x} y={HEIGHT - PAD.bottom + 14}
            fontSize="10" textAnchor="middle"
            fill="currentColor" fillOpacity={0.7}
          >
            {formatTime(t)}
          </text>
        );
      })}

      {/* Series path */}
      <path d={path} fill="none" stroke="var(--primary)" strokeWidth={2} />

      {/* Point markers */}
      {series.map((p, i) => (
        <circle
          key={i}
          cx={xScale(new Date(p.t).getTime())}
          cy={yScale(p.y)}
          r={2.5}
          fill="var(--primary)"
        >
          <title>
            {new Date(p.t).toLocaleString()} — {fmt(p.y)}
          </title>
        </circle>
      ))}
    </svg>
  );
}

/**
 * Evenly-spaced tick values between {@code lo} and {@code hi}. Not "nice"
 * aligned to power-of-ten steps — good enough for short ranges and the
 * small tick count we use.
 */
function buildTicks(lo: number, hi: number, count: number): number[] {
  if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo === hi) return [lo];
  const step = (hi - lo) / count;
  const out: number[] = [];
  for (let i = 0; i <= count; i++) out.push(lo + step * i);
  return out;
}

function formatTime(ms: number): string {
  const d = new Date(ms);
  const hh = d.getHours().toString().padStart(2, '0');
  const mm = d.getMinutes().toString().padStart(2, '0');
  return `${hh}:${mm}`;
}
