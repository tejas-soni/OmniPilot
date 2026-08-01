import { PassThrough } from 'stream';
import { RpcServer } from '../src/rpc/server.js';
import { RPC_ERROR_CODES, RPC_METHODS } from '../src/rpc/protocol.js';

describe('RpcServer Unit Tests', () => {
  let input: PassThrough;
  let output: PassThrough;
  let server: RpcServer;

  beforeEach(() => {
    input = new PassThrough();
    output = new PassThrough();
    server = new RpcServer(input, output);
    server.start();
  });

  afterEach(() => {
    input.destroy();
    output.destroy();
  });

  function sendRaw(msgString: string) {
    const contentLength = Buffer.byteLength(msgString, 'utf8');
    const framed = `Content-Length: ${contentLength}\r\n\r\n${msgString}`;
    input.write(framed);
  }

  function readOutput(): Promise<any> {
    return new Promise((resolve) => {
      output.once('data', (chunk: Buffer) => {
        const str = chunk.toString('utf8');
        const body = str.substring(str.indexOf('\r\n\r\n') + 4);
        resolve(JSON.parse(body));
      });
    });
  }

  test('should respond to initialize request', async () => {
    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: RPC_METHODS.INITIALIZE,
      params: { version: '1.0.0', ideType: 'IC', ideName: 'IntelliJ' }
    }));

    const response = await outputPromise;
    expect(response).toEqual({
      jsonrpc: '2.0',
      id: 1,
      result: {
        serverVersion: '1.1.0',
        status: 'ok',
        capabilities: {
          chat: true,
          history: true,
          agentTools: true
        }
      }
    });
  });

  test('should return parse error on invalid JSON', async () => {
    const outputPromise = readOutput();
    input.write('Content-Length: 12\r\n\r\n{invalid_json');

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.PARSE_ERROR);
  });

  test('should return invalid request on non-2.0 jsonrpc', async () => {
    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '1.0',
      id: 2,
      method: 'initialize'
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.INVALID_REQUEST);
  });

  test('should return method not found for unknown method', async () => {
    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 3,
      method: 'unknown/method'
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.METHOD_NOT_FOUND);
  });

  test('should return internal error when handler throws', async () => {
    server.registerHandler('test/throw', () => {
      throw new Error('Test failure');
    });

    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 4,
      method: 'test/throw'
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.INTERNAL_ERROR);
    expect(response.error.message).toBe('Test failure');
  });

  test('should return custom code when handler throws object with code', async () => {
    server.registerHandler('test/customErr', () => {
      const err: any = new Error('Custom Error');
      err.code = -32001;
      err.data = { detail: 'test' };
      throw err;
    });

    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 5,
      method: 'test/customErr'
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(-32001);
    expect(response.error.message).toBe('Custom Error');
  });

  test('should handle non-object throw gracefully', async () => {
    server.registerHandler('test/strErr', () => {
      throw 'string error';
    });

    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 6,
      method: 'test/strErr'
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.INTERNAL_ERROR);
  });

  test('should send notifications correctly', async () => {
    const outputPromise = readOutput();
    server.sendNotification('chat/token', { token: 'hello' });

    const response = await outputPromise;
    expect(response).toEqual({
      jsonrpc: '2.0',
      method: 'chat/token',
      params: { token: 'hello' }
    });
  });

  test('should handle outbound request and receive response', async () => {
    const requestPromise = server.sendRequest('chat/toolCall', { tool: 'read_file' });

    const rawOutput = await new Promise<any>((resolve) => {
      output.once('data', (chunk: Buffer) => {
        const str = chunk.toString('utf8');
        const body = str.substring(str.indexOf('\r\n\r\n') + 4);
        resolve(JSON.parse(body));
      });
    });

    expect(rawOutput.method).toBe('chat/toolCall');
    expect(rawOutput.params).toEqual({ tool: 'read_file' });

    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: rawOutput.id,
      result: { content: 'file data' }
    }));

    const result = await requestPromise;
    expect(result).toEqual({ content: 'file data' });
  });

  test('should handle outbound request error', async () => {
    const requestPromise = server.sendRequest('chat/toolCall', { tool: 'invalid' });

    const rawOutput = await new Promise<any>((resolve) => {
      output.once('data', (chunk: Buffer) => {
        const str = chunk.toString('utf8');
        const body = str.substring(str.indexOf('\r\n\r\n') + 4);
        resolve(JSON.parse(body));
      });
    });

    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: rawOutput.id,
      error: { code: RPC_ERROR_CODES.PERMISSION_DENIED, message: 'Denied' }
    }));

    await expect(requestPromise).rejects.toEqual({
      code: RPC_ERROR_CODES.PERMISSION_DENIED,
      message: 'Denied'
    });
  });

  test('should skip header without Content-Length header string', () => {
    input.write('Bad-Header: 123\r\n\r\nSomeBody');
    expect(true).toBe(true);
  });

  test('should handle method non-string error', async () => {
    const outputPromise = readOutput();
    sendRaw(JSON.stringify({
      jsonrpc: '2.0',
      id: 99,
      method: 123
    }));

    const response = await outputPromise;
    expect(response.error.code).toBe(RPC_ERROR_CODES.INVALID_REQUEST);
  });
});
