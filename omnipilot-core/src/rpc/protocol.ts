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

export const RPC_ERROR_CODES = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  PERMISSION_DENIED: -32001,
  LLM_STREAM_ERROR: -32002
} as const;

export const RPC_METHODS = {
  INITIALIZE: 'initialize',
  CHAT_SEND: 'chat/send',
  CHAT_CANCEL: 'chat/cancel',
  HISTORY_LIST: 'history/list',
  HISTORY_LOAD: 'history/load',
  HISTORY_SAVE: 'history/save',
  HISTORY_DELETE: 'history/delete',
  HISTORY_CLEAR: 'history/clear',
  CONFIG_SET_PROVIDERS: 'config/setProviders',
  CONFIG_SET_API_KEY: 'config/setApiKey',
  MODELS_FETCH: 'models/fetch',

  // Server -> Client notifications / requests
  NOTIFY_CHAT_TOKEN: 'chat/token',
  NOTIFY_CHAT_COMPLETE: 'chat/complete',
  NOTIFY_CHAT_ERROR: 'chat/error',
  REQ_CHAT_TOOL_CALL: 'chat/toolCall',
  REQ_PERMISSION: 'chat/permissionRequest',

  // Client -> Server tool execution result
  RESP_TOOL_RESULT: 'tool/result',
  RESP_PERMISSION: 'tool/permissionResponse'
} as const;

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
