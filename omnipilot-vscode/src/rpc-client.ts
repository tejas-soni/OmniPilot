import * as child_process from 'child_process';
import * as path from 'path';
import * as vscode from 'vscode';

export type NotificationHandler = (params: any) => void;

export class OmniPilotRpcClient {
  private proc: child_process.ChildProcess | null = null;
  private buffer: Buffer = Buffer.alloc(0);
  private nextId = 1;
  private pending = new Map<number, { resolve: (v: any) => void; reject: (e: any) => void }>();
  private notifHandlers = new Map<string, NotificationHandler[]>();
  private serverPath: string;

  constructor(extensionUri: vscode.Uri) {
    this.serverPath = path.join(
      extensionUri.fsPath, '..', 'omnipilot-core', 'dist', 'omnipilot-server.js'
    );
  }

  public start(): void {
    if (this.proc) return;
    this.proc = child_process.spawn(process.execPath, [this.serverPath], {
      stdio: ['pipe', 'pipe', 'pipe']
    });

    this.proc.stdout?.on('data', (chunk: Buffer) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.drain();
    });

    this.proc.stderr?.on('data', (d: Buffer) => {
      console.error('[OmniPilot Server]', d.toString());
    });

    this.proc.on('exit', (code) => {
      console.warn('[OmniPilot] Server exited with code', code);
      this.proc = null;
    });
  }

  public stop(): void {
    this.proc?.kill();
    this.proc = null;
  }

  private drain(): void {
    while (true) {
      const headerEnd = this.buffer.indexOf('\r\n\r\n');
      if (headerEnd === -1) break;
      const header = this.buffer.subarray(0, headerEnd).toString();
      const match = header.match(/Content-Length:\s*(\d+)/i);
      if (!match) { this.buffer = this.buffer.subarray(headerEnd + 4); continue; }
      const len = parseInt(match[1], 10);
      if (this.buffer.length < headerEnd + 4 + len) break;
      const body = this.buffer.subarray(headerEnd + 4, headerEnd + 4 + len).toString();
      this.buffer = this.buffer.subarray(headerEnd + 4 + len);
      this.handleMessage(body);
    }
  }

  private handleMessage(raw: string): void {
    let msg: any;
    try { msg = JSON.parse(raw); } catch { return; }

    // Response to our outgoing request
    if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined) && !msg.method) {
      const p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        msg.error ? p.reject(msg.error) : p.resolve(msg.result);
      }
      return;
    }

    // Notification from server
    if (msg.method && msg.id === undefined) {
      const handlers = this.notifHandlers.get(msg.method) ?? [];
      handlers.forEach(h => h(msg.params));
    }
  }

  public sendRequest<T = any>(method: string, params?: any): Promise<T> {
    return new Promise((resolve, reject) => {
      if (!this.proc) { reject(new Error('Server not running')); return; }
      const id = this.nextId++;
      this.pending.set(id, { resolve, reject });
      this.send({ jsonrpc: '2.0', id, method, params });
    });
  }

  public onNotification(method: string, handler: NotificationHandler): void {
    if (!this.notifHandlers.has(method)) this.notifHandlers.set(method, []);
    this.notifHandlers.get(method)!.push(handler);
  }

  public offNotification(method: string, handler: NotificationHandler): void {
    const arr = this.notifHandlers.get(method);
    if (arr) this.notifHandlers.set(method, arr.filter(h => h !== handler));
  }

  private send(msg: any): void {
    const json = JSON.stringify(msg);
    const header = `Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n`;
    this.proc?.stdin?.write(header + json);
  }
}
