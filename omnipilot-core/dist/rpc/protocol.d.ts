export interface JsonRpcRequest<T = any> {
    jsonrpc: '2.0';
    id?: string | number;
    method: string;
    params?: T;
}
export interface JsonRpcResponse<T = any> {
    jsonrpc: '2.0';
    id: string | number;
    result?: T;
    error?: JsonRpcError;
}
export interface JsonRpcNotification<T = any> {
    jsonrpc: '2.0';
    method: string;
    params?: T;
}
export interface JsonRpcError {
    code: number;
    message: string;
    data?: any;
}
export declare const RPC_ERROR_CODES: {
    readonly PARSE_ERROR: -32700;
    readonly INVALID_REQUEST: -32600;
    readonly METHOD_NOT_FOUND: -32601;
    readonly INVALID_PARAMS: -32602;
    readonly INTERNAL_ERROR: -32603;
    readonly PERMISSION_DENIED: -32001;
    readonly LLM_STREAM_ERROR: -32002;
};
export declare const RPC_METHODS: {
    readonly INITIALIZE: "initialize";
    readonly CHAT_SEND: "chat/send";
    readonly CHAT_CANCEL: "chat/cancel";
    readonly HISTORY_LIST: "history/list";
    readonly HISTORY_LOAD: "history/load";
    readonly HISTORY_SAVE: "history/save";
    readonly HISTORY_DELETE: "history/delete";
    readonly HISTORY_CLEAR: "history/clear";
    readonly CONFIG_SET_PROVIDERS: "config/setProviders";
    readonly CONFIG_SET_API_KEY: "config/setApiKey";
    readonly MODELS_FETCH: "models/fetch";
    readonly NOTIFY_CHAT_TOKEN: "chat/token";
    readonly NOTIFY_CHAT_COMPLETE: "chat/complete";
    readonly NOTIFY_CHAT_ERROR: "chat/error";
    readonly REQ_CHAT_TOOL_CALL: "chat/toolCall";
    readonly REQ_PERMISSION: "chat/permissionRequest";
    readonly RESP_TOOL_RESULT: "tool/result";
    readonly RESP_PERMISSION: "tool/permissionResponse";
};
export interface InitializeParams {
    version: string;
    ideType: string;
    ideName: string;
}
export interface InitializeResult {
    serverVersion: string;
    status: 'ok';
    capabilities: {
        chat: boolean;
        history: boolean;
        agentTools: boolean;
    };
}
