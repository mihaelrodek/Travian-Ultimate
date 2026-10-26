import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import ServerPickerCard from '../features/servers/ServerPickerCard';
import { useCurrentServer } from '../hooks/useCurrentServer';
import { intelApi } from '../api/intel';
import { TRIBE_LABELS, type WorldMap, type WorldMapVillage } from '../types/intel';

/**
 * Interactive world map (roadmap MAP1). Renders every settled village from the
 * parsed {@code map.sql} data on a Canvas with pan (drag) and zoom (wheel).
 * Villages are coloured by tribe, inactive players dimmed, capitals ringed.
 * Hovering a tile shows its details; the cursor's game coordinates are read out
 * live. Server-scoped like the other intel pages.
 *
 * Rendering notes: the map math is done in CSS pixels and the backing store is
 * scaled by devicePixelRatio for crisp dots. Villages sit at unique integer
 * tiles, so hover resolves via an O(1) {@code "x,y" -> village} lookup rather
 * than scanning the whole array. Draws are coalesced through requestAnimationFrame.
 */

const DOT_MIN_PX = 1.5;
const DOT_MAX_PX = 7;
const MAX_SCALE = 40; // px per field, fully zoomed in
const DEFAULT_RADIUS = 200;

/** Tribe → dot colour. Readable on the dark canvas; Natars stay grey. */
const TRIBE_COLORS: Record<number, string> = {
  1: '#5aa0ff', // Romans — blue
  2: '#ff7a5c', // Teutons — orange-red
  3: '#49d17f', // Gauls — green
  5: '#c8ccd6', // Natars — grey
  6: '#ffd05a', // Egyptians — gold
  7: '#b98bff', // Huns — purple
};
const UNKNOWN_COLOR = '#8a91a3';

interface View {
  scale: number; // pixels per field
  cx: number; // game-x at canvas centre
  cy: number; // game-y at canvas centre
}

interface Hover {
  village: WorldMapVillage;
  clientX: number;
  clientY: number;
}

export default function MapPage() {
  const { t } = useTranslation();
  const { serverId, setCurrentServer } = useCurrentServer();

  const [data, setData] = useState<WorldMap | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [hover, setHover] = useState<Hover | null>(null);
  const [cursorCoord, setCursorCoord] = useState<{ x: number; y: number } | null>(null);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const viewRef = useRef<View>({ scale: 2, cx: 0, cy: 0 });
  const sizeRef = useRef<{ w: number; h: number }>({ w: 0, h: 0 });
  const rafRef = useRef<number | null>(null);
  const hoverKeyRef = useRef<string | null>(null);
  const dragRef = useRef<{ x: number; y: number } | null>(null);

  const radius = data?.mapRadius ?? DEFAULT_RADIUS;

  // Fast hover lookup + colour precompute, rebuilt only when the data changes.
  const byCoord = useMemo(() => {
    const m = new Map<string, WorldMapVillage>();
    if (data) for (const v of data.villages) m.set(`${v.x},${v.y}`, v);
    return m;
  }, [data]);

  // ---- data load ----
  useEffect(() => {
    if (serverId == null) {
      setData(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    intelApi
      .worldMap(serverId)
      .then((d) => {
        if (cancelled) return;
        setData(d);
        fitToMap(d.mapRadius ?? DEFAULT_RADIUS);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load map');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId]);

  const scheduleDraw = useCallback(() => {
    if (rafRef.current != null) return;
    rafRef.current = window.requestAnimationFrame(() => {
      rafRef.current = null;
      draw();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const fitToMap = useCallback((r: number) => {
    const { w, h } = sizeRef.current;
    const min = Math.min(w, h);
    const scale = min > 0 ? (min * 0.92) / (2 * r) : 2;
    viewRef.current = { scale, cx: 0, cy: 0 };
    scheduleDraw();
  }, [scheduleDraw]);

  // ---- canvas sizing (responsive + HiDPI) ----
  useEffect(() => {
    const container = containerRef.current;
    const canvas = canvasRef.current;
    if (!container || !canvas) return;

    const resize = () => {
      const rect = container.getBoundingClientRect();
      const w = Math.max(1, Math.floor(rect.width));
      const h = Math.max(1, Math.floor(rect.height));
      sizeRef.current = { w, h };
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.floor(w * dpr);
      canvas.height = Math.floor(h * dpr);
      canvas.style.width = `${w}px`;
      canvas.style.height = `${h}px`;
      const ctx = canvas.getContext('2d');
      if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // Keep the view sane on first sizing.
      if (viewRef.current.scale <= 0) fitToMap(radius);
      scheduleDraw();
    };

    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(container);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [radius, fitToMap, scheduleDraw]);

  // Redraw when data lands.
  useEffect(() => {
    scheduleDraw();
  }, [data, scheduleDraw]);

  // ---- coordinate transforms (game y is north-up, so screen y is inverted) ----
  const toScreen = (gx: number, gy: number, v: View, w: number, h: number) => ({
    sx: w / 2 + (gx - v.cx) * v.scale,
    sy: h / 2 - (gy - v.cy) * v.scale,
  });
  const toGame = (sx: number, sy: number, v: View, w: number, h: number) => ({
    gx: v.cx + (sx - w / 2) / v.scale,
    gy: v.cy - (sy - h / 2) / v.scale,
  });

  // ---- render ----
  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const { w, h } = sizeRef.current;
    const v = viewRef.current;

    ctx.clearRect(0, 0, w, h);
    // Background
    ctx.fillStyle = 'rgba(8, 12, 22, 0.65)';
    ctx.fillRect(0, 0, w, h);

    drawGrid(ctx, v, w, h, radius);

    if (data) {
      // Viewport bounds in game coords (+1 field margin), for culling when zoomed in.
      const tl = toGame(0, 0, v, w, h);
      const br = toGame(w, h, v, w, h);
      const gxMin = Math.floor(tl.gx) - 1;
      const gxMax = Math.ceil(br.gx) + 1;
      const gyMin = Math.floor(br.gy) - 1; // br has smaller game-y (bottom)
      const gyMax = Math.ceil(tl.gy) + 1;

      const dot = Math.max(DOT_MIN_PX, Math.min(DOT_MAX_PX, v.scale * 0.6));
      const half = dot / 2;

      // Group by colour+alpha to minimise fillStyle churn across tens of thousands of dots.
      const groups = new Map<string, { color: string; alpha: number; pts: WorldMapVillage[] }>();
      for (const village of data.villages) {
        if (village.x < gxMin || village.x > gxMax || village.y < gyMin || village.y > gyMax) continue;
        const color = village.tribeId != null ? TRIBE_COLORS[village.tribeId] ?? UNKNOWN_COLOR : UNKNOWN_COLOR;
        const alpha = village.inactive ? 0.32 : 0.95;
        const key = `${color}|${alpha}`;
        let g = groups.get(key);
        if (!g) {
          g = { color, alpha, pts: [] };
          groups.set(key, g);
        }
        g.pts.push(village);
      }

      for (const g of groups.values()) {
        ctx.globalAlpha = g.alpha;
        ctx.fillStyle = g.color;
        for (const village of g.pts) {
          const { sx, sy } = toScreen(village.x, village.y, v, w, h);
          ctx.fillRect(sx - half, sy - half, dot, dot);
        }
      }
      ctx.globalAlpha = 1;

      // Capitals get a subtle ring, but only when zoomed in enough to matter.
      if (v.scale >= 3) {
        ctx.strokeStyle = 'rgba(255,255,255,0.75)';
        ctx.lineWidth = 1;
        for (const village of data.villages) {
          if (!village.capital) continue;
          if (village.x < gxMin || village.x > gxMax || village.y < gyMin || village.y > gyMax) continue;
          const { sx, sy } = toScreen(village.x, village.y, v, w, h);
          ctx.beginPath();
          ctx.arc(sx, sy, half + 2, 0, Math.PI * 2);
          ctx.stroke();
        }
      }
    }

    // Hover ring
    if (hoverKeyRef.current) {
      const hv = byCoord.get(hoverKeyRef.current);
      if (hv) {
        const { sx, sy } = toScreen(hv.x, hv.y, v, w, h);
        ctx.strokeStyle = '#36e6ff';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(sx, sy, Math.max(5, v.scale * 0.6), 0, Math.PI * 2);
        ctx.stroke();
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, radius, byCoord]);

  // ---- interaction ----
  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    dragRef.current = { x: e.clientX, y: e.clientY };
    hoverKeyRef.current = null;
    setHover(null);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const { w, h } = sizeRef.current;
    const v = viewRef.current;

    if (dragRef.current) {
      const dx = e.clientX - dragRef.current.x;
      const dy = e.clientY - dragRef.current.y;
      dragRef.current = { x: e.clientX, y: e.clientY };
      v.cx -= dx / v.scale;
      v.cy += dy / v.scale; // screen-down = game-south
      scheduleDraw();
      return;
    }

    const { gx, gy } = toGame(mx, my, v, w, h);
    setCursorCoord({ x: Math.round(gx), y: Math.round(gy) });

    // Search the rounded tile + 8 neighbours, pick the closest within a few px.
    const rx = Math.round(gx);
    const ry = Math.round(gy);
    let best: WorldMapVillage | null = null;
    let bestPx = 9;
    for (let ox = -1; ox <= 1; ox++) {
      for (let oy = -1; oy <= 1; oy++) {
        const cand = byCoord.get(`${rx + ox},${ry + oy}`);
        if (!cand) continue;
        const s = toScreen(cand.x, cand.y, v, w, h);
        const d = Math.hypot(s.sx - mx, s.sy - my);
        if (d < bestPx) {
          bestPx = d;
          best = cand;
        }
      }
    }
    const newKey = best ? `${best.x},${best.y}` : null;
    if (newKey !== hoverKeyRef.current) {
      hoverKeyRef.current = newKey;
      scheduleDraw();
    }
    setHover(best ? { village: best, clientX: e.clientX, clientY: e.clientY } : null);
  };

  const endDrag = (e: React.PointerEvent<HTMLCanvasElement>) => {
    dragRef.current = null;
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      /* pointer already released */
    }
  };

  const onWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const { w, h } = sizeRef.current;
    const v = viewRef.current;

    // Keep the game point under the cursor fixed across the zoom.
    const before = toGame(mx, my, v, w, h);
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    const minScale = (Math.min(w, h) * 0.92) / (2 * radius) / 2;
    v.scale = Math.max(minScale, Math.min(MAX_SCALE, v.scale * factor));
    const after = toGame(mx, my, v, w, h);
    v.cx += before.gx - after.gx;
    v.cy += before.gy - after.gy;
    scheduleDraw();
  };

  const onSync = async () => {
    if (serverId == null) return;
    setSyncing(true);
    setError(null);
    try {
      await intelApi.sync(serverId);
      const d = await intelApi.worldMap(serverId);
      setData(d);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sync failed');
    } finally {
      setSyncing(false);
    }
  };

  const lastSynced = data?.lastSyncedAt ? new Date(data.lastSyncedAt).toLocaleString() : null;

  return (
    <div className="stack">
      <div>
        <GlassHeader title={t('map.title')} subtitle={t('map.subtitle')} />
      </div>

      <ServerPickerCard serverId={serverId} onChange={setCurrentServer} />

      {serverId == null && (
        <Card>
          <p className="muted">{t('map.pickServer')}</p>
        </Card>
      )}

      {serverId != null && (
        <Card>
          <div className="toolbar" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'center', marginBottom: '0.5rem' }}>
            <button type="button" className="btn btn--ghost btn--sm" onClick={() => fitToMap(radius)}>
              {t('map.recenter')}
            </button>
            <button type="button" className="btn btn--ghost btn--sm" onClick={() => void onSync()} disabled={syncing || loading}>
              {syncing ? t('map.syncing') : t('map.sync')}
            </button>
            <span className="muted" style={{ fontSize: '0.85rem' }}>
              {loading
                ? t('common.loading')
                : t('map.villageCount', { count: data?.villageCount ?? 0 })}
              {lastSynced && ` · ${t('map.lastSynced', { when: lastSynced })}`}
            </span>
            <span className="muted" style={{ marginLeft: 'auto', fontFamily: 'monospace', fontSize: '0.85rem' }}>
              {cursorCoord ? `(${cursorCoord.x}|${cursorCoord.y})` : '(—|—)'}
            </span>
          </div>

          {error && <p className="form__msg form__msg--error">{error}</p>}
          {!loading && !error && data && data.villageCount === 0 && (
            <p className="muted">{t('map.empty')}</p>
          )}

          <div
            ref={containerRef}
            style={{
              position: 'relative',
              width: '100%',
              height: '68vh',
              minHeight: '360px',
              borderRadius: '10px',
              overflow: 'hidden',
              border: '1px solid var(--border, rgba(255,255,255,0.08))',
            }}
          >
            <canvas
              ref={canvasRef}
              style={{ display: 'block', cursor: dragRef.current ? 'grabbing' : 'grab', touchAction: 'none' }}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
              onPointerLeave={() => {
                if (!dragRef.current) {
                  hoverKeyRef.current = null;
                  setHover(null);
                  setCursorCoord(null);
                  scheduleDraw();
                }
              }}
              onWheel={onWheel}
            />

            {hover && (
              <div
                style={{
                  position: 'fixed',
                  left: Math.min(hover.clientX + 14, window.innerWidth - 240),
                  top: hover.clientY + 14,
                  zIndex: 40,
                  pointerEvents: 'none',
                  maxWidth: '230px',
                  padding: '0.5rem 0.65rem',
                  borderRadius: '8px',
                  background: 'rgba(10, 14, 24, 0.96)',
                  border: '1px solid rgba(54,230,255,0.35)',
                  boxShadow: '0 6px 24px rgba(0,0,0,0.5)',
                  fontSize: '0.8rem',
                  lineHeight: 1.35,
                }}
              >
                <div style={{ fontWeight: 700 }}>
                  {hover.village.name}{' '}
                  <span className="muted">({hover.village.x}|{hover.village.y})</span>
                </div>
                <div>{hover.village.playerName ?? <span className="muted">— unknown player —</span>}</div>
                {hover.village.allianceName && <div className="muted">[{hover.village.allianceName}]</div>}
                <div className="muted">
                  {hover.village.tribeId != null ? TRIBE_LABELS[hover.village.tribeId] ?? `Tribe ${hover.village.tribeId}` : '—'}
                  {' · '}
                  {t('map.pop')}: {hover.village.population}
                  {hover.village.capital ? ' · ★' : ''}
                </div>
                {hover.village.inactive && (
                  <div><span className="pill pill--success">{t('map.inactive')}</span></div>
                )}
              </div>
            )}
          </div>

          <MapLegend />
        </Card>
      )}
    </div>
  );
}

function MapLegend() {
  const { t } = useTranslation();
  const items = [
    { id: 1, label: 'Romans' },
    { id: 2, label: 'Teutons' },
    { id: 3, label: 'Gauls' },
    { id: 6, label: 'Egyptians' },
    { id: 7, label: 'Huns' },
    { id: 5, label: 'Natars' },
  ];
  return (
    <div className="toolbar" style={{ gap: '0.9rem', flexWrap: 'wrap', marginTop: '0.6rem', fontSize: '0.78rem' }}>
      {items.map((it) => (
        <span key={it.id} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
          <span style={{ width: 10, height: 10, borderRadius: 2, background: TRIBE_COLORS[it.id], display: 'inline-block' }} />
          {it.label}
        </span>
      ))}
      <span className="muted" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
        <span style={{ width: 10, height: 10, borderRadius: 2, background: UNKNOWN_COLOR, opacity: 0.32, display: 'inline-block' }} />
        {t('map.inactiveDimmed')}
      </span>
    </div>
  );
}

/** Adaptive grid: lines every N fields (N grows as you zoom out), axes through 0. */
function drawGrid(ctx: CanvasRenderingContext2D, v: View, w: number, h: number, radius: number) {
  const targetPx = 90; // desired spacing between grid lines
  const rawStep = targetPx / v.scale;
  const step = niceStep(rawStep);

  const left = v.cx - w / 2 / v.scale;
  const right = v.cx + w / 2 / v.scale;
  const top = v.cy + h / 2 / v.scale;
  const bottom = v.cy - h / 2 / v.scale;

  ctx.lineWidth = 1;
  ctx.strokeStyle = 'rgba(255,255,255,0.05)';
  ctx.fillStyle = 'rgba(255,255,255,0.35)';
  ctx.font = '10px monospace';

  const startX = Math.ceil(left / step) * step;
  for (let gx = startX; gx <= right; gx += step) {
    const sx = w / 2 + (gx - v.cx) * v.scale;
    ctx.beginPath();
    ctx.moveTo(sx, 0);
    ctx.lineTo(sx, h);
    ctx.stroke();
    if (Math.abs(gx) <= radius + step) ctx.fillText(String(Math.round(gx)), sx + 2, 11);
  }
  const startY = Math.ceil(bottom / step) * step;
  for (let gy = startY; gy <= top; gy += step) {
    const sy = h / 2 - (gy - v.cy) * v.scale;
    ctx.beginPath();
    ctx.moveTo(0, sy);
    ctx.lineTo(w, sy);
    ctx.stroke();
    if (Math.abs(gy) <= radius + step) ctx.fillText(String(Math.round(gy)), 2, sy - 2);
  }

  // Axes through the origin.
  ctx.strokeStyle = 'rgba(54,230,255,0.25)';
  const ax = w / 2 + (0 - v.cx) * v.scale;
  const ay = h / 2 - (0 - v.cy) * v.scale;
  ctx.beginPath();
  ctx.moveTo(ax, 0);
  ctx.lineTo(ax, h);
  ctx.moveTo(0, ay);
  ctx.lineTo(w, ay);
  ctx.stroke();
}

/** Round a raw step up to the nearest 1/2/5 × 10ⁿ so grid labels stay tidy. */
function niceStep(raw: number): number {
  if (raw <= 1) return 1;
  const pow = Math.pow(10, Math.floor(Math.log10(raw)));
  const norm = raw / pow;
  const nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
  return nice * pow;
}
