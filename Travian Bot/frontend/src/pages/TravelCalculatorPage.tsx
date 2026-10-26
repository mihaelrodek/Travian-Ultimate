import { useMemo, useState } from 'react';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';

/**
 * Standalone coord-to-coord travel time calculator (roadmap CALC2).
 *
 * Pure client-side math — no backend round-trip for a square root:
 *   distance      torus distance on the wrapped Travian map (the world is a
 *                 donut: x/y wrap at the edges, so 195|0 → -195|0 is 11
 *                 fields, not 390).
 *   travel time   distance / (unitSpeed × serverSpeed), with the Tournament
 *                 Square bonus applied only to the part of the journey beyond
 *                 20 fields: t = 20/v + (d−20)/(v·(1+0.2·L)).
 *
 * Unit speeds are the Travian Legends base values (fields/hour at 1× server
 * speed). Picking a unit just prefills the speed field — the value stays
 * editable so odd servers, artifacts or hero-with-boots setups can be typed
 * in directly.
 */

interface UnitSpeed {
  name: string;
  speed: number;
}

const TRIBE_UNITS: Record<string, UnitSpeed[]> = {
  Romans: [
    { name: 'Legionnaire', speed: 6 },
    { name: 'Praetorian', speed: 5 },
    { name: 'Imperian', speed: 7 },
    { name: 'Equites Legati', speed: 16 },
    { name: 'Equites Imperatoris', speed: 14 },
    { name: 'Equites Caesaris', speed: 10 },
    { name: 'Battering Ram', speed: 4 },
    { name: 'Fire Catapult', speed: 3 },
    { name: 'Senator', speed: 4 },
    { name: 'Settler', speed: 5 },
  ],
  Teutons: [
    { name: 'Clubswinger', speed: 7 },
    { name: 'Spearman', speed: 7 },
    { name: 'Axeman', speed: 6 },
    { name: 'Scout', speed: 9 },
    { name: 'Paladin', speed: 10 },
    { name: 'Teutonic Knight', speed: 9 },
    { name: 'Ram', speed: 4 },
    { name: 'Catapult', speed: 3 },
    { name: 'Chief', speed: 4 },
    { name: 'Settler', speed: 5 },
  ],
  Gauls: [
    { name: 'Phalanx', speed: 7 },
    { name: 'Swordsman', speed: 6 },
    { name: 'Pathfinder', speed: 17 },
    { name: 'Theutates Thunder', speed: 19 },
    { name: 'Druidrider', speed: 16 },
    { name: 'Haeduan', speed: 13 },
    { name: 'Ram', speed: 4 },
    { name: 'Trebuchet', speed: 3 },
    { name: 'Chieftain', speed: 5 },
    { name: 'Settler', speed: 5 },
  ],
  Egyptians: [
    { name: 'Slave Militia', speed: 7 },
    { name: 'Ash Warden', speed: 6 },
    { name: 'Khopesh Warrior', speed: 7 },
    { name: 'Sopdu Explorer', speed: 16 },
    { name: 'Anhur Guard', speed: 15 },
    { name: 'Resheph Chariot', speed: 10 },
    { name: 'Ram', speed: 4 },
    { name: 'Stone Catapult', speed: 3 },
    { name: 'Nomarch', speed: 4 },
    { name: 'Settler', speed: 5 },
  ],
  Huns: [
    { name: 'Mercenary', speed: 6 },
    { name: 'Bowman', speed: 6 },
    { name: 'Spotter', speed: 19 },
    { name: 'Steppe Rider', speed: 16 },
    { name: 'Marksman', speed: 15 },
    { name: 'Marauder', speed: 14 },
    { name: 'Ram', speed: 4 },
    { name: 'Catapult', speed: 3 },
    { name: 'Logades', speed: 5 },
    { name: 'Settler', speed: 5 },
  ],
};

const TRIBES = Object.keys(TRIBE_UNITS);

/** Distance past which the Tournament Square speed bonus kicks in. */
const TS_FREE_FIELDS = 20;

export default function TravelCalculatorPage() {
  const [fromX, setFromX] = useState('0');
  const [fromY, setFromY] = useState('0');
  const [toX, setToX] = useState('0');
  const [toY, setToY] = useState('0');
  const [tribe, setTribe] = useState(TRIBES[0]);
  const [unitName, setUnitName] = useState(TRIBE_UNITS[TRIBES[0]][0].name);
  const [speed, setSpeed] = useState(String(TRIBE_UNITS[TRIBES[0]][0].speed));
  const [serverSpeed, setServerSpeed] = useState('1');
  const [tsLevel, setTsLevel] = useState('0');
  const [mapSize, setMapSize] = useState('401');
  const [landingLocal, setLandingLocal] = useState('');

  const onPickTribe = (next: string) => {
    setTribe(next);
    const first = TRIBE_UNITS[next][0];
    setUnitName(first.name);
    setSpeed(String(first.speed));
  };

  const onPickUnit = (next: string) => {
    setUnitName(next);
    const unit = TRIBE_UNITS[tribe].find((u) => u.name === next);
    if (unit) setSpeed(String(unit.speed));
  };

  const result = useMemo(() => {
    const x1 = Number.parseInt(fromX, 10);
    const y1 = Number.parseInt(fromY, 10);
    const x2 = Number.parseInt(toX, 10);
    const y2 = Number.parseInt(toY, 10);
    const v = Number.parseFloat(speed);
    const sx = Number.parseFloat(serverSpeed);
    const ts = Number.parseInt(tsLevel, 10);
    const size = Number.parseInt(mapSize, 10);
    if ([x1, y1, x2, y2, v, sx, ts, size].some(Number.isNaN)) return null;
    if (v <= 0 || sx <= 0 || size <= 0 || ts < 0 || ts > 20) return null;

    // Torus distance — the map wraps at the edges.
    let dx = Math.abs(x1 - x2);
    let dy = Math.abs(y1 - y2);
    dx = Math.min(dx, size - dx);
    dy = Math.min(dy, size - dy);
    const distance = Math.sqrt(dx * dx + dy * dy);

    // TS bonus only applies to the stretch beyond 20 fields.
    const effective = v * sx;
    const boosted = effective * (1 + 0.2 * ts);
    const hours =
      distance <= TS_FREE_FIELDS
        ? distance / effective
        : TS_FREE_FIELDS / effective + (distance - TS_FREE_FIELDS) / boosted;
    const seconds = Math.round(hours * 3600);

    const now = new Date();
    const arrival = new Date(now.getTime() + seconds * 1000);

    let launchAt: Date | null = null;
    let launchInPast = false;
    if (landingLocal !== '') {
      const landing = new Date(landingLocal);
      if (!Number.isNaN(landing.getTime())) {
        launchAt = new Date(landing.getTime() - seconds * 1000);
        launchInPast = launchAt.getTime() < now.getTime();
      }
    }

    return { distance, seconds, arrival, launchAt, launchInPast };
  }, [fromX, fromY, toX, toY, speed, serverSpeed, tsLevel, mapSize, landingLocal]);

  return (
    <div className="stack">
      <div>
        <GlassHeader
          title="Travel time"
          subtitle="Koliko treba jedinici od A do B — s Tournament Square bonusom."
        />
      </div>

      <Card title="Route">
        <div className="form">
          <div className="form__row">
            <CoordPair label="From" x={fromX} y={fromY} onX={setFromX} onY={setFromY} />
            <CoordPair label="To" x={toX} y={toY} onX={setToX} onY={setToY} />
          </div>

          <div className="form__row">
            <div className="form__field">
              <label>Tribe</label>
              <select value={tribe} onChange={(e) => onPickTribe(e.target.value)}>
                {TRIBES.map((tr) => (
                  <option key={tr} value={tr}>{tr}</option>
                ))}
              </select>
            </div>
            <div className="form__field">
              <label>Unit</label>
              <select value={unitName} onChange={(e) => onPickUnit(e.target.value)}>
                {TRIBE_UNITS[tribe].map((u) => (
                  <option key={u.name} value={u.name}>
                    {u.name} ({u.speed}/h)
                  </option>
                ))}
              </select>
            </div>
            <div className="form__field">
              <label>Speed (fields/h)</label>
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={speed}
                onChange={(e) => setSpeed(e.target.value)}
                style={{ width: '7rem' }}
              />
            </div>
          </div>

          <div className="form__row">
            <div className="form__field">
              <label>Server speed</label>
              <input
                type="number"
                min={1}
                step={1}
                value={serverSpeed}
                onChange={(e) => setServerSpeed(e.target.value)}
                style={{ width: '6rem' }}
                title="x1 / x2 / x3 server multiplier — multiplies unit speed."
              />
            </div>
            <div className="form__field">
              <label>Tournament Square level</label>
              <input
                type="number"
                min={0}
                max={20}
                value={tsLevel}
                onChange={(e) => setTsLevel(e.target.value)}
                style={{ width: '6rem' }}
                title="+20% speed per level, applied only beyond 20 fields."
              />
            </div>
            <div className="form__field">
              <label>Map size</label>
              <input
                type="number"
                min={3}
                value={mapSize}
                onChange={(e) => setMapSize(e.target.value)}
                style={{ width: '6rem' }}
                title="401 on standard servers (coords −200…200). Distance wraps at the edges."
              />
            </div>
            <div className="form__field">
              <label>Target landing time (optional)</label>
              <input
                type="datetime-local"
                step={1}
                value={landingLocal}
                onChange={(e) => setLandingLocal(e.target.value)}
              />
            </div>
          </div>
        </div>

        {result === null ? (
          <p className="form__msg form__msg--error">Enter valid numbers — speed and server speed must be positive, TS level 0–20.</p>
        ) : (
          <>
            <hr className="card__sep" />
            <dl className="kv">
              <div>
                <dt>Distance</dt>
                <dd>{result.distance.toFixed(2)} fields</dd>
              </div>
              <div>
                <dt>Travel time</dt>
                <dd><strong>{formatDuration(result.seconds)}</strong></dd>
              </div>
              <div>
                <dt>Arrives (if sent now)</dt>
                <dd>{result.arrival.toLocaleString()}</dd>
              </div>
              {result.launchAt && (
                <div>
                  <dt>Send at (for target landing)</dt>
                  <dd>
                    <strong>{result.launchAt.toLocaleString()}</strong>
                    {result.launchInPast && (
                      <span className="form__msg form__msg--error" style={{ marginLeft: '0.5rem' }}>
                        already in the past
                      </span>
                    )}
                  </dd>
                </div>
              )}
            </dl>
          </>
        )}
      </Card>

      <Card title="Notes">
        <ul className="muted" style={{ margin: 0, paddingLeft: '1.2rem', fontSize: '0.85rem' }}>
          <li>Unit speeds are Travian Legends base values; picking a unit prefills the speed but you can overwrite it for special servers or artifact effects.</li>
          <li>The Tournament Square bonus (+20%/level) only affects the part of the trip beyond 20 fields.</li>
          <li>Distance is computed on the wrapped (torus) map — the Attack Planner uses plain straight-line distance, so the two can differ near map edges.</li>
        </ul>
      </Card>
    </div>
  );
}

function CoordPair({
  label,
  x,
  y,
  onX,
  onY,
}: {
  label: string;
  x: string;
  y: string;
  onX: (v: string) => void;
  onY: (v: string) => void;
}) {
  return (
    <div className="form__field">
      <label>{label}</label>
      <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
        <input
          type="number"
          value={x}
          onChange={(e) => onX(e.target.value)}
          style={{ width: '5.5rem' }}
          aria-label={`${label} X`}
          placeholder="x"
        />
        <span className="muted">|</span>
        <input
          type="number"
          value={y}
          onChange={(e) => onY(e.target.value)}
          style={{ width: '5.5rem' }}
          aria-label={`${label} Y`}
          placeholder="y"
        />
      </div>
    </div>
  );
}

/** "2h 13m 05s" — seconds always shown; a snipe cares about them. */
function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  if (h > 0) return `${h}h ${mm}m ${ss}s`;
  if (m > 0) return `${m}m ${ss}s`;
  return `${s}s`;
}
