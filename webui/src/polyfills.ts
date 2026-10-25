// Older Android System WebViews lack a few built-ins that UI libraries assume.
// Loaded before anything else (see main.tsx).

/* eslint-disable @typescript-eslint/no-explicit-any */
const AP = Array.prototype as any;

if (!AP.at) {
  AP.at = function (this: unknown[], n: number) {
    const i = Math.trunc(n) || 0;
    return this[i < 0 ? this.length + i : i];
  };
}
if (!AP.findLast) {
  AP.findLast = function (this: unknown[], fn: (v: unknown, i: number, a: unknown[]) => boolean) {
    for (let i = this.length - 1; i >= 0; i--) if (fn(this[i], i, this)) return this[i];
    return undefined;
  };
}
if (!AP.toSorted) {
  AP.toSorted = function (this: unknown[], cmp?: (a: unknown, b: unknown) => number) {
    return this.slice().sort(cmp);
  };
}
if (!(Object as any).hasOwn) {
  (Object as any).hasOwn = (o: object, k: PropertyKey) => Object.prototype.hasOwnProperty.call(o, k);
}
if (typeof (globalThis as any).structuredClone !== "function") {
  (globalThis as any).structuredClone = (v: unknown) => JSON.parse(JSON.stringify(v));
}

export {};
