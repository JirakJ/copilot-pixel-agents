import type { IExtensionBridge, ExtensionMessage } from './extensionBridge.js';

declare global {
  interface Window {
    cefQuery?: (request: { request: string; onSuccess?: (response: string) => void; onFailure?: (errorCode: number, errorMessage: string) => void }) => void;
    __onExtensionMessage?: (data: ExtensionMessage) => void;
  }
}

export class JcefBridge implements IExtensionBridge {
  send(msg: ExtensionMessage): void {
    window.cefQuery?.({
      request: JSON.stringify(msg),
      onSuccess: () => {},
      onFailure: (_code, err) => console.error('[JcefBridge] send failed:', err),
    });
  }

  subscribe(handler: (msg: ExtensionMessage) => void): () => void {
    window.__onExtensionMessage = (data: ExtensionMessage) => {
      handler(data);
    };
    return () => {
      delete window.__onExtensionMessage;
    };
  }
}
