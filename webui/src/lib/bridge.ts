// Connection to the Kotlin host (WebBridge.kt).
// Outside the app (vite dev, screenshots) a mock host stands in; see mock.ts.

type Handler = (event: Record<string, any>) => void; // eslint-disable-line @typescript-eslint/no-explicit-any

interface Native {
  request(json: string): void;
}

interface Pending {
  resolve: (v: unknown) => void;
  reject: (e: Error) => void;
}

declare global {
  interface Window {
    PoorDSHNative?: Native;
    __poordsh?: {
      onResponse(msg: { id: number; ok: boolean; result?: unknown; error?: string }): void;
      onEvent(event: Record<string, unknown>): void;
      onBack(): boolean;
    };
  }
}

let nextId = 1;
const pending = new Map<number, Pending>();
const listeners = new Set<Handler>();
let backHandler: (() => boolean) | null = null;
let mockHost: ((method: string, params: Record<string, unknown>) => Promise<unknown>) | null = null;

export function installBridge() {
  window.__poordsh = {
    onResponse(msg) {
      const p = pending.get(msg.id);
      if (!p) return;
      pending.delete(msg.id);
      if (msg.ok) p.resolve(msg.result ?? null);
      else p.reject(new Error(msg.error || "Request failed"));
    },
    onEvent(event) {
      listeners.forEach((l) => {
        try {
          l(event);
        } catch (e) {
          console.error(e);
        }
      });
    },
    onBack() {
      return backHandler ? backHandler() : false;
    },
  };
}

export const isNative = () => typeof window.PoorDSHNative?.request === "function";

export async function call<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  if (isNative()) {
    const id = nextId++;
    return new Promise<T>((resolve, reject) => {
      pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
      window.PoorDSHNative!.request(JSON.stringify({ id, method, params }));
    });
  }
  if (!mockHost) {
    const mock = await import("./mock");
    mockHost = mock.createMockHost((e) => window.__poordsh?.onEvent(e));
  }
  return (await mockHost(method, params)) as T;
}

export function onEvent(handler: Handler): () => void {
  listeners.add(handler);
  return () => listeners.delete(handler);
}

export function setBackHandler(handler: (() => boolean) | null) {
  backHandler = handler;
}
