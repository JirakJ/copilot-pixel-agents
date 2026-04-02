import { runtime } from '../runtime.js';
import type { IExtensionBridge } from './extensionBridge.js';
import { BrowserBridge } from './browserBridge.js';
import { JcefBridge } from './jcefBridge.js';
import { VsCodeBridge } from './vscodeBridge.js';

export type { IExtensionBridge, ExtensionMessage } from './extensionBridge.js';

function createBridge(): IExtensionBridge {
  switch (runtime) {
    case 'vscode':
      return new VsCodeBridge();
    case 'jcef':
      return new JcefBridge();
    case 'browser':
      return new BrowserBridge();
  }
}

export const bridge: IExtensionBridge = createBridge();
