export interface ExtensionMessage {
  type: string;
  [key: string]: unknown;
}

export interface IExtensionBridge {
  send(msg: ExtensionMessage): void;
  subscribe(handler: (msg: ExtensionMessage) => void): () => void;
}
