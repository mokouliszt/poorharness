import { Component, type ReactNode } from "react";

/** Last-resort screen so a rendering bug never leaves a blank black page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="flex h-full flex-col justify-center gap-4 p-6">
        <p className="text-lg font-semibold text-paper">Something broke in the UI.</p>
        <pre className="max-h-64 overflow-auto rounded-xl bg-surface p-3 font-mono text-xs text-mute">
          {String(this.state.error.stack || this.state.error.message)}
        </pre>
        <button className="h-11 rounded-full bg-paper font-medium text-void" onClick={() => location.reload()}>
          Reload
        </button>
      </div>
    );
  }
}
