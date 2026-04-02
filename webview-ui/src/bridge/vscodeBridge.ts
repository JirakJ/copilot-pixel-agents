import type { IExtensionBridge, ExtensionMessage } from './extensionBridge.js';

declare function acquireVsCodeApi(): { postMessage(msg: unknown): void };

export class VsCodeBridge implements IExtensionBridge {
  private readonly api = acquireVsCodeApi();

  send(msg: ExtensionMessage): void {
    this.api.postMessage(msg);
  }

  subscribe(handler: (msg: ExtensionMessage) => void): () => void {
    const listener = (e: MessageEvent) => handler(e.data);
    window.addEventListener('message', listener);
    return () => window.removeEventListener('message', listener);
  }
}
