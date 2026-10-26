import { createContext, useContext, useEffect, useRef, type ReactNode } from 'react';

/**
 * App-wide Server-Sent Events client (roadmap NOT4). One {@link EventSource} is
 * opened for the whole app; components subscribe to named events via
 * {@link useLiveEvent} to update the instant something happens on the backend,
 * instead of waiting for their poll interval. The REST polls stay as a
 * fallback, so a dropped stream just means "slightly less live".
 *
 * The browser's EventSource reconnects on its own if the connection drops, and
 * the registered per-event listeners survive those internal reconnects, so
 * there's no reconnect logic to hand-roll here.
 */

type Handler = (data: unknown) => void;

class EventStreamClient {
  private source: EventSource | null = null;
  private readonly handlers = new Map<string, Set<Handler>>();
  private readonly nativeListeners = new Map<string, EventListener>();

  private ensureSource(): void {
    if (this.source) return;
    this.source = new EventSource('/api/events/stream');
    // Re-arm native listeners for any names registered before the source existed.
    for (const name of this.handlers.keys()) this.attachNative(name);
  }

  private attachNative(name: string): void {
    if (!this.source || this.nativeListeners.has(name)) return;
    const listener: EventListener = (evt) => {
      const msg = evt as MessageEvent;
      let data: unknown = null;
      try {
        data = msg.data ? JSON.parse(msg.data as string) : null;
      } catch {
        data = msg.data;
      }
      this.handlers.get(name)?.forEach((h) => {
        try {
          h(data);
        } catch {
          /* a bad listener must not break the others */
        }
      });
    };
    this.nativeListeners.set(name, listener);
    this.source.addEventListener(name, listener);
  }

  subscribe(name: string, handler: Handler): () => void {
    this.ensureSource();
    let set = this.handlers.get(name);
    if (!set) {
      set = new Set();
      this.handlers.set(name, set);
      this.attachNative(name);
    }
    set.add(handler);
    return () => {
      this.handlers.get(name)?.delete(handler);
    };
  }

  close(): void {
    this.source?.close();
    this.source = null;
    this.nativeListeners.clear();
  }
}

const EventStreamCtx = createContext<EventStreamClient | null>(null);

export function EventStreamProvider({ children }: { children: ReactNode }) {
  const clientRef = useRef<EventStreamClient | null>(null);
  if (clientRef.current === null) clientRef.current = new EventStreamClient();

  useEffect(() => {
    const client = clientRef.current;
    return () => client?.close();
  }, []);

  return <EventStreamCtx.Provider value={clientRef.current}>{children}</EventStreamCtx.Provider>;
}

/**
 * Subscribe to a named live event for the lifetime of the calling component.
 * The handler may change every render (it's read through a ref) without
 * re-subscribing, so passing an inline arrow is fine.
 */
export function useLiveEvent(eventName: string, handler: Handler): void {
  const client = useContext(EventStreamCtx);
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!client) return;
    return client.subscribe(eventName, (data) => handlerRef.current(data));
  }, [client, eventName]);
}
