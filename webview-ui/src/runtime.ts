/**
 * Runtime detection, provider-agnostic
 *
 * Single source of truth for determining whether the webview is running
 * inside an IDE extension (VS Code, Cursor, Windsurf, etc.), JetBrains JCEF,
 * or standalone in a browser.
 */

declare function acquireVsCodeApi(): unknown;

export type Runtime = 'vscode' | 'jcef' | 'browser';

export const runtime: Runtime =
  typeof acquireVsCodeApi !== 'undefined' ? 'vscode' :
  typeof (window as any).cefQuery !== 'undefined' ? 'jcef' :
  'browser';

export const isBrowserRuntime = runtime === 'browser';
export const isJcefRuntime = runtime === 'jcef';
export const isVscodeRuntime = runtime === 'vscode';
