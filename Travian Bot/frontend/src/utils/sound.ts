/**
 * Tiny Web Audio "alert" beep (roadmap NOT3). Synthesised at call time so
 * there's no audio asset to bundle or fetch. Used for critical events (a new
 * incoming attack) when the user has opted into sound alerts.
 *
 * The AudioContext is created lazily and reused. Browser autoplay policy may
 * leave a context "suspended" until the first user gesture — since the app is
 * a dashboard the user has clicked into, a resume() attempt is enough in
 * practice; if it's still blocked we fail silently rather than throw.
 */

type WebkitWindow = Window & { webkitAudioContext?: typeof AudioContext };

let ctx: AudioContext | null = null;

function audioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext ?? (window as WebkitWindow).webkitAudioContext;
  if (!Ctor) return null;
  if (ctx === null) {
    try {
      ctx = new Ctor();
    } catch {
      return null;
    }
  }
  return ctx;
}

/**
 * Play a short two-tone "ping" — a higher note followed by a lower one, which
 * reads as an alert without being a harsh single beep. No-op (never throws) if
 * Web Audio is unavailable or blocked by autoplay policy.
 */
export function playAlertSound(): void {
  const ac = audioContext();
  if (!ac) return;
  // A suspended context (pre-gesture) won't produce sound; try to wake it.
  if (ac.state === 'suspended') {
    void ac.resume().catch(() => {});
  }
  try {
    const now = ac.currentTime;
    playTone(ac, 880, now, 0.18);        // A5
    playTone(ac, 620, now + 0.2, 0.22);  // ~D#5
  } catch {
    /* audio hiccup — a missed beep is never worth surfacing */
  }
}

function playTone(ac: AudioContext, freq: number, start: number, duration: number): void {
  const osc = ac.createOscillator();
  const gain = ac.createGain();
  osc.type = 'sine';
  osc.frequency.value = freq;
  // Quick attack, exponential release — avoids the click of a hard cutoff.
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(0.25, start + 0.02);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
  osc.connect(gain).connect(ac.destination);
  osc.start(start);
  osc.stop(start + duration + 0.02);
}
