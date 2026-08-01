import { Readable, Writable } from 'stream';
export type RequestHandler = (params: any) => Promise<any> | any;
export declare class RpcServer {
    private handlers;
    private pendingRequests;
    private buffer;
    private nextRequestId;
    private input;
    private output;
    constructor(input?: Readable, output?: Writable);
    private registerDefaultHandlers;
    registerHandler(method: string, handler: RequestHandler): void;
    start(): void;
    parseBuffer(): void;
    handleRawMessage(rawJson: string): void;
    sendNotification(method: string, params?: any): void;
    sendRequest<T = any>(method: string, params?: any): Promise<T>;
    private sendResponse;
    private sendError;
    private sendFramedMessage;
}
