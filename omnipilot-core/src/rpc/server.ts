import { Readable, Writable } from 'stream';
import {
  JsonRpcRequest,
  JsonRpcResponse,
  JsonRpcNotification,
  JsonRpcError,
  RPC_ERROR_CODES,
  RPC_METHODS,
  InitializeParams,
  InitializeResult
} from './protocol.js';

export type RequestHandler = (params: any) => Promise<any> | any;

export class RpcServer {
  private handlers: Map<string, RequestHandler> = new Map();
  private pendingRequests: Map<string | number, { resolve: (val: any) => void; reject: (err: any) => void }> = new Map();
  private buffer: Buffer = Buffer.alloc(0);
  private nextRequestId: number = 1;
  private input: Readable;
  private output: Writable;

  constructor(input: Readable = process.stdin, output: Writable = process.stdout) {
    this.input = input;
    this.output = output;
    this.registerDefaultHandlers();
  }

  private registerDefaultHandlers(): void {
    this.registerHandler(RPC_METHODS.INITIALIZE, (params: InitializeParams): InitializeResult => {
      return {
        serverVersion: '1.1.0',
        status: 'ok',
        capabilities: {
          chat: true,
          history: true,
          agentTools: true
        }
      };
    });
  }

  public registerHandler(method: string, handler: RequestHandler): void {
    this.handlers.set(method, handler);
  }

  public start(): void {
    this.input.on('data', (chunk: Buffer) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.parseBuffer();
    });
  }

  public parseBuffer(): void {
    while (true) {
      const headerEnd = this.buffer.indexOf('\r\n\r\n');
      if (headerEnd === -1) break;

      const headerString = this.buffer.subarray(0, headerEnd).toString('utf8');
      const contentLengthMatch = headerString.match(/Content-Length:\s*(\d+)/i);

      if (!contentLengthMatch) {
        // Invalid header format, clear buffer up to headerEnd + 4
        this.buffer = this.buffer.subarray(headerEnd + 4);
        continue;
      }

      const contentLength = parseInt(contentLengthMatch[1], 10);
      const totalLength = headerEnd + 4 + contentLength;

      if (this.buffer.length < totalLength) {
        // Wait for more data
        break;
      }

      const bodyBuffer = this.buffer.subarray(headerEnd + 4, totalLength);
      this.buffer = this.buffer.subarray(totalLength);

      this.handleRawMessage(bodyBuffer.toString('utf8'));
    }
  }

  public handleRawMessage(rawJson: string): void {
    let msg: any;
    try {
      msg = JSON.parse(rawJson);
    } catch (e) {
      this.sendError(null, RPC_ERROR_CODES.PARSE_ERROR, 'Parse error: invalid JSON');
      return;
    }

    if (!msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') {
      this.sendError(msg?.id ?? null, RPC_ERROR_CODES.INVALID_REQUEST, 'Invalid Request: jsonrpc must be "2.0"');
      return;
    }

    // Is it a response to an outbound request we sent?
    if ('id' in msg && ('result' in msg || 'error' in msg) && !('method' in msg)) {
      const pending = this.pendingRequests.get(msg.id);
      if (pending) {
        this.pendingRequests.delete(msg.id);
        if (msg.error) {
          pending.reject(msg.error);
        } else {
          pending.resolve(msg.result);
        }
      }
      return;
    }

    // Request or Notification
    const method = msg.method;
    if (typeof method !== 'string') {
      this.sendError(msg.id ?? null, RPC_ERROR_CODES.INVALID_REQUEST, 'Method name must be a string');
      return;
    }

    const handler = this.handlers.get(method);

    if (!handler) {
      if ('id' in msg && msg.id !== null) {
        this.sendError(msg.id, RPC_ERROR_CODES.METHOD_NOT_FOUND, `Method not found: ${method}`);
      }
      return;
    }

    // Execute Handler
    Promise.resolve()
      .then(() => handler(msg.params))
      .then((result) => {
        if ('id' in msg && msg.id !== null) {
          this.sendResponse(msg.id, result);
        }
      })
      .catch((err) => {
        if ('id' in msg && msg.id !== null) {
          const code = err?.code ?? RPC_ERROR_CODES.INTERNAL_ERROR;
          const message = err?.message ?? 'Internal server error';
          this.sendError(msg.id, code, message, err?.data);
        }
      });
  }

  public sendNotification(method: string, params?: any): void {
    const notification: JsonRpcNotification = {
      jsonrpc: '2.0',
      method,
      params
    };
    this.sendFramedMessage(notification);
  }

  public sendRequest<T = any>(method: string, params?: any): Promise<T> {
    const id = this.nextRequestId++;
    const request: JsonRpcRequest = {
      jsonrpc: '2.0',
      id,
      method,
      params
    };

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(id, { resolve, reject });
      this.sendFramedMessage(request);
    });
  }

  private sendResponse(id: string | number, result: any): void {
    const response: JsonRpcResponse = {
      jsonrpc: '2.0',
      id,
      result
    };
    this.sendFramedMessage(response);
  }

  private sendError(id: string | number | null, code: number, message: string, data?: any): void {
    const response: any = {
      jsonrpc: '2.0',
      id,
      error: { code, message, ...(data !== undefined ? { data } : {}) }
    };
    this.sendFramedMessage(response);
  }

  private sendFramedMessage(msg: any): void {
    const jsonStr = JSON.stringify(msg);
    const contentLength = Buffer.byteLength(jsonStr, 'utf8');
    const header = `Content-Length: ${contentLength}\r\n\r\n`;
    this.output.write(header + jsonStr);
  }
}
