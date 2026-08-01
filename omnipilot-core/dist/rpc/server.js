"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.RpcServer = void 0;
const protocol_js_1 = require("./protocol.js");
class RpcServer {
    handlers = new Map();
    pendingRequests = new Map();
    buffer = Buffer.alloc(0);
    nextRequestId = 1;
    input;
    output;
    constructor(input = process.stdin, output = process.stdout) {
        this.input = input;
        this.output = output;
        this.registerDefaultHandlers();
    }
    registerDefaultHandlers() {
        this.registerHandler(protocol_js_1.RPC_METHODS.INITIALIZE, (params) => {
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
    registerHandler(method, handler) {
        this.handlers.set(method, handler);
    }
    start() {
        this.input.on('data', (chunk) => {
            this.buffer = Buffer.concat([this.buffer, chunk]);
            this.parseBuffer();
        });
    }
    parseBuffer() {
        while (true) {
            const headerEnd = this.buffer.indexOf('\r\n\r\n');
            if (headerEnd === -1)
                break;
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
    handleRawMessage(rawJson) {
        let msg;
        try {
            msg = JSON.parse(rawJson);
        }
        catch (e) {
            this.sendError(null, protocol_js_1.RPC_ERROR_CODES.PARSE_ERROR, 'Parse error: invalid JSON');
            return;
        }
        if (!msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') {
            this.sendError(msg?.id ?? null, protocol_js_1.RPC_ERROR_CODES.INVALID_REQUEST, 'Invalid Request: jsonrpc must be "2.0"');
            return;
        }
        // Is it a response to an outbound request we sent?
        if ('id' in msg && ('result' in msg || 'error' in msg) && !('method' in msg)) {
            const pending = this.pendingRequests.get(msg.id);
            if (pending) {
                this.pendingRequests.delete(msg.id);
                if (msg.error) {
                    pending.reject(msg.error);
                }
                else {
                    pending.resolve(msg.result);
                }
            }
            return;
        }
        // Request or Notification
        const method = msg.method;
        if (typeof method !== 'string') {
            this.sendError(msg.id ?? null, protocol_js_1.RPC_ERROR_CODES.INVALID_REQUEST, 'Method name must be a string');
            return;
        }
        const handler = this.handlers.get(method);
        if (!handler) {
            if ('id' in msg && msg.id !== null) {
                this.sendError(msg.id, protocol_js_1.RPC_ERROR_CODES.METHOD_NOT_FOUND, `Method not found: ${method}`);
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
                const code = err?.code ?? protocol_js_1.RPC_ERROR_CODES.INTERNAL_ERROR;
                const message = err?.message ?? 'Internal server error';
                this.sendError(msg.id, code, message, err?.data);
            }
        });
    }
    sendNotification(method, params) {
        const notification = {
            jsonrpc: '2.0',
            method,
            params
        };
        this.sendFramedMessage(notification);
    }
    sendRequest(method, params) {
        const id = this.nextRequestId++;
        const request = {
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
    sendResponse(id, result) {
        const response = {
            jsonrpc: '2.0',
            id,
            result
        };
        this.sendFramedMessage(response);
    }
    sendError(id, code, message, data) {
        const response = {
            jsonrpc: '2.0',
            id,
            error: { code, message, ...(data !== undefined ? { data } : {}) }
        };
        this.sendFramedMessage(response);
    }
    sendFramedMessage(msg) {
        const jsonStr = JSON.stringify(msg);
        const contentLength = Buffer.byteLength(jsonStr, 'utf8');
        const header = `Content-Length: ${contentLength}\r\n\r\n`;
        this.output.write(header + jsonStr);
    }
}
exports.RpcServer = RpcServer;
