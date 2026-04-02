import type { IExtensionBridge, ExtensionMessage } from './extensionBridge.js';

export class BrowserBridge implements IExtensionBridge {
  send(msg: ExtensionMessage): void {
    console.log('[BrowserBridge.send]', msg);
  }

  subscribe(_handler: (msg: ExtensionMessage) => void): () => void {
    return () => {};
  }
}
