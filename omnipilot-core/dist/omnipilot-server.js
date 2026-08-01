"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// src/rpc/protocol.ts
var RPC_ERROR_CODES = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  PERMISSION_DENIED: -32001,
  LLM_STREAM_ERROR: -32002
};
var RPC_METHODS = {
  INITIALIZE: "initialize",
  CHAT_SEND: "chat/send",
  CHAT_CANCEL: "chat/cancel",
  HISTORY_LIST: "history/list",
  HISTORY_LOAD: "history/load",
  HISTORY_SAVE: "history/save",
  HISTORY_DELETE: "history/delete",
  HISTORY_CLEAR: "history/clear",
  CONFIG_SET_PROVIDERS: "config/setProviders",
  CONFIG_SET_API_KEY: "config/setApiKey",
  MODELS_FETCH: "models/fetch",
  // Server -> Client notifications / requests
  NOTIFY_CHAT_TOKEN: "chat/token",
  NOTIFY_CHAT_COMPLETE: "chat/complete",
  NOTIFY_CHAT_ERROR: "chat/error",
  REQ_CHAT_TOOL_CALL: "chat/toolCall",
  REQ_PERMISSION: "chat/permissionRequest",
  // Client -> Server tool execution result
  RESP_TOOL_RESULT: "tool/result",
  RESP_PERMISSION: "tool/permissionResponse"
};

// src/rpc/server.ts
var RpcServer = class {
  handlers = /* @__PURE__ */ new Map();
  pendingRequests = /* @__PURE__ */ new Map();
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
    this.registerHandler(RPC_METHODS.INITIALIZE, (params) => {
      return {
        serverVersion: "1.1.0",
        status: "ok",
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
    this.input.on("data", (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.parseBuffer();
    });
  }
  parseBuffer() {
    while (true) {
      const headerEnd = this.buffer.indexOf("\r\n\r\n");
      if (headerEnd === -1)
        break;
      const headerString = this.buffer.subarray(0, headerEnd).toString("utf8");
      const contentLengthMatch = headerString.match(/Content-Length:\s*(\d+)/i);
      if (!contentLengthMatch) {
        this.buffer = this.buffer.subarray(headerEnd + 4);
        continue;
      }
      const contentLength = parseInt(contentLengthMatch[1], 10);
      const totalLength = headerEnd + 4 + contentLength;
      if (this.buffer.length < totalLength) {
        break;
      }
      const bodyBuffer = this.buffer.subarray(headerEnd + 4, totalLength);
      this.buffer = this.buffer.subarray(totalLength);
      this.handleRawMessage(bodyBuffer.toString("utf8"));
    }
  }
  handleRawMessage(rawJson) {
    let msg;
    try {
      msg = JSON.parse(rawJson);
    } catch (e) {
      this.sendError(null, RPC_ERROR_CODES.PARSE_ERROR, "Parse error: invalid JSON");
      return;
    }
    if (!msg || typeof msg !== "object" || msg.jsonrpc !== "2.0") {
      this.sendError(msg?.id ?? null, RPC_ERROR_CODES.INVALID_REQUEST, 'Invalid Request: jsonrpc must be "2.0"');
      return;
    }
    if ("id" in msg && ("result" in msg || "error" in msg) && !("method" in msg)) {
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
    const method = msg.method;
    if (typeof method !== "string") {
      this.sendError(msg.id ?? null, RPC_ERROR_CODES.INVALID_REQUEST, "Method name must be a string");
      return;
    }
    const handler = this.handlers.get(method);
    if (!handler) {
      if ("id" in msg && msg.id !== null) {
        this.sendError(msg.id, RPC_ERROR_CODES.METHOD_NOT_FOUND, `Method not found: ${method}`);
      }
      return;
    }
    Promise.resolve().then(() => handler(msg.params)).then((result) => {
      if ("id" in msg && msg.id !== null) {
        this.sendResponse(msg.id, result);
      }
    }).catch((err) => {
      if ("id" in msg && msg.id !== null) {
        const code = err?.code ?? RPC_ERROR_CODES.INTERNAL_ERROR;
        const message = err?.message ?? "Internal server error";
        this.sendError(msg.id, code, message, err?.data);
      }
    });
  }
  sendNotification(method, params) {
    const notification = {
      jsonrpc: "2.0",
      method,
      params
    };
    this.sendFramedMessage(notification);
  }
  sendRequest(method, params) {
    const id = this.nextRequestId++;
    const request = {
      jsonrpc: "2.0",
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
      jsonrpc: "2.0",
      id,
      result
    };
    this.sendFramedMessage(response);
  }
  sendError(id, code, message, data) {
    const response = {
      jsonrpc: "2.0",
      id,
      error: { code, message, ...data !== void 0 ? { data } : {} }
    };
    this.sendFramedMessage(response);
  }
  sendFramedMessage(msg) {
    const jsonStr = JSON.stringify(msg);
    const contentLength = Buffer.byteLength(jsonStr, "utf8");
    const header = `Content-Length: ${contentLength}\r
\r
`;
    this.output.write(header + jsonStr);
  }
};

// src/history/manager.ts
var fs = __toESM(require("fs"), 1);
var path = __toESM(require("path"), 1);
var HistoryManager = class {
  storageDir;
  filePath;
  constructor(storageDir) {
    this.storageDir = storageDir || path.join(process.env.HOME || process.env.USERPROFILE || ".", ".omnipilot");
    this.filePath = path.join(this.storageDir, "omnipilot_chat_history.json");
    this.ensureDirectoryExists();
  }
  ensureDirectoryExists() {
    if (!fs.existsSync(this.storageDir)) {
      fs.mkdirSync(this.storageDir, { recursive: true });
    }
  }
  loadStore() {
    try {
      if (fs.existsSync(this.filePath)) {
        const data = fs.readFileSync(this.filePath, "utf8");
        const parsed = JSON.parse(data);
        return { sessions: Array.isArray(parsed.sessions) ? parsed.sessions : [] };
      }
    } catch (e) {
    }
    return { sessions: [] };
  }
  saveStore(store) {
    this.ensureDirectoryExists();
    fs.writeFileSync(this.filePath, JSON.stringify(store, null, 2), "utf8");
  }
  getSessions() {
    const store = this.loadStore();
    return store.sessions.sort((a, b) => b.timestamp - a.timestamp).map((s) => ({
      id: s.id,
      title: s.title,
      timestamp: s.timestamp
    }));
  }
  getSession(id) {
    const store = this.loadStore();
    return store.sessions.find((s) => s.id === id) || null;
  }
  saveSession(session) {
    const store = this.loadStore();
    const existingIndex = store.sessions.findIndex((s) => s.id === session.id);
    if (existingIndex >= 0) {
      store.sessions[existingIndex] = session;
    } else {
      store.sessions.push(session);
    }
    this.saveStore(store);
  }
  deleteSession(id) {
    const store = this.loadStore();
    store.sessions = store.sessions.filter((s) => s.id !== id);
    this.saveStore(store);
  }
  clearAll() {
    this.saveStore({ sessions: [] });
  }
};

// src/config/provider-store.ts
var ProviderStore = class {
  providers = /* @__PURE__ */ new Map();
  activeProviderId = "";
  setProviders(providers) {
    this.providers.clear();
    for (const p of providers) {
      this.providers.set(p.id, p);
    }
    if (providers.length > 0 && !this.activeProviderId) {
      this.activeProviderId = providers[0].id;
    }
  }
  setApiKey(providerId, apiKey) {
    const provider = this.providers.get(providerId);
    if (provider) {
      provider.apiKey = apiKey;
    }
  }
  getProvider(id) {
    return this.providers.get(id);
  }
  getActiveProvider() {
    return this.providers.get(this.activeProviderId);
  }
  setActiveProviderId(id) {
    if (this.providers.has(id)) {
      this.activeProviderId = id;
    }
  }
  getAllProviders() {
    return Array.from(this.providers.values());
  }
};

// src/llm/client.ts
var LlmClient = class {
  activeAbortController = null;
  cancelStream() {
    if (this.activeAbortController) {
      this.activeAbortController.abort();
      this.activeAbortController = null;
    }
  }
  async fetchModels(baseUrl, apiKey) {
    const cleanUrl = baseUrl.endsWith("/") ? baseUrl.slice(0, -1) : baseUrl;
    const targetUrl = `${cleanUrl}/models`;
    const headers = {
      "Accept": "application/json"
    };
    if (apiKey && apiKey.trim().length > 0) {
      headers["Authorization"] = `Bearer ${apiKey.trim()}`;
    }
    const response = await fetch(targetUrl, { headers });
    if (!response.ok) {
      throw new Error(`Failed to fetch models: HTTP ${response.status} ${response.statusText}`);
    }
    const json = await response.json();
    if (json && Array.isArray(json.data)) {
      return json.data.map((m) => m.id || m.name).filter(Boolean);
    }
    return [];
  }
  async streamChatCompletion(provider, requestPayload, callbacks) {
    this.cancelStream();
    this.activeAbortController = new AbortController();
    const signal = this.activeAbortController.signal;
    const cleanUrl = provider.baseUrl.endsWith("/") ? provider.baseUrl.slice(0, -1) : provider.baseUrl;
    const targetUrl = `${cleanUrl}/chat/completions`;
    const headers = {
      "Content-Type": "application/json",
      "Accept": "text/event-stream"
    };
    if (provider.apiKey && provider.apiKey.trim().length > 0) {
      headers["Authorization"] = `Bearer ${provider.apiKey.trim()}`;
    }
    let fullText = "";
    try {
      const response = await fetch(targetUrl, {
        method: "POST",
        headers,
        body: JSON.stringify({ ...requestPayload, stream: true }),
        signal
      });
      if (!response.ok) {
        const errBody = await response.text().catch(() => "");
        const errorMsg = `HTTP ${response.status} ${response.statusText}: ${errBody}`;
        callbacks.onError(errorMsg);
        return "";
      }
      if (!response.body) {
        callbacks.onError("Response body is null");
        return "";
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done)
          break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(":"))
            continue;
          if (trimmed.startsWith("data: ")) {
            const dataStr = trimmed.slice(6).trim();
            if (dataStr === "[DONE]") {
              callbacks.onComplete();
              return fullText;
            }
            try {
              const chunk = JSON.parse(dataStr);
              const deltaContent = chunk.choices?.[0]?.delta?.content;
              if (deltaContent) {
                fullText += deltaContent;
                callbacks.onToken(deltaContent);
              }
            } catch (e) {
            }
          }
        }
      }
      callbacks.onComplete();
      return fullText;
    } catch (e) {
      if (e.name === "AbortError") {
        callbacks.onComplete();
      } else {
        callbacks.onError(e.message || "Network stream error");
      }
      return fullText;
    } finally {
      this.activeAbortController = null;
    }
  }
};

// src/llm/prompt-builder.ts
var PromptBuilder = class {
  static buildSystemPrompt(context) {
    const { mode, osInfo, editorContext } = context;
    let basePrompt = `You are OmniPilot, an advanced AI coding assistant embedded in the IDE.
Operating System: ${osInfo}.
`;
    if (mode === "READ-ONLY") {
      basePrompt += `Mode: READ-ONLY.
You must ONLY analyze and explain code. Do NOT invoke any file editing or command execution tools.
`;
    } else if (mode === "CHAT") {
      basePrompt += `Mode: CHAT.
Provide clear, helpful coding guidance, suggestions, and explanations.
`;
    } else {
      basePrompt += `Mode: AGENT.
You have access to autonomous tools to inspect files, make edits, and execute terminal commands.
Always inspect files before editing and verify your work.
`;
    }
    if (editorContext) {
      basePrompt += `
--- CURRENT EDITOR CONTEXT ---
`;
      if (editorContext.file) {
        basePrompt += `Active File: ${editorContext.file} (${editorContext.language || "unknown"})
`;
      }
      if (editorContext.selectedText) {
        basePrompt += `Selected Code:
\`\`\`
${editorContext.selectedText}
\`\`\`
`;
      } else if (editorContext.content) {
        basePrompt += `Document Content (Truncated if long):
\`\`\`
${editorContext.content.substring(0, 4e3)}
\`\`\`
`;
      }
    }
    return basePrompt;
  }
  static normalizeMessagesForClaude(messages, systemPrompt) {
    const result = [];
    let systemAdded = false;
    for (const msg of messages) {
      if (msg.role === "system") {
        if (!systemAdded) {
          result.push({ role: "system", content: systemPrompt + "\n" + (msg.content || "") });
          systemAdded = true;
        }
      } else {
        result.push(msg);
      }
    }
    if (!systemAdded) {
      result.unshift({ role: "system", content: systemPrompt });
    }
    return result;
  }
};

// src/index.ts
var server = new RpcServer(process.stdin, process.stdout);
var historyManager = new HistoryManager();
var providerStore = new ProviderStore();
var llmClient = new LlmClient();
server.registerHandler(RPC_METHODS.HISTORY_LIST, () => {
  return historyManager.getSessions();
});
server.registerHandler(RPC_METHODS.HISTORY_LOAD, (params) => {
  return historyManager.getSession(params?.id);
});
server.registerHandler(RPC_METHODS.HISTORY_SAVE, (params) => {
  if (params?.session) {
    historyManager.saveSession(params.session);
  }
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.HISTORY_DELETE, (params) => {
  if (params?.id) {
    historyManager.deleteSession(params.id);
  }
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.HISTORY_CLEAR, () => {
  historyManager.clearAll();
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.CONFIG_SET_PROVIDERS, (params) => {
  if (Array.isArray(params?.providers)) {
    providerStore.setProviders(params.providers);
  }
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.CONFIG_SET_API_KEY, (params) => {
  if (params?.providerId) {
    providerStore.setApiKey(params.providerId, params.apiKey);
  }
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.MODELS_FETCH, async (params) => {
  if (!params?.baseUrl) {
    throw new Error("baseUrl is required");
  }
  return await llmClient.fetchModels(params.baseUrl, params.apiKey);
});
server.registerHandler(RPC_METHODS.CHAT_CANCEL, () => {
  llmClient.cancelStream();
  return { status: "ok" };
});
server.registerHandler(RPC_METHODS.CHAT_SEND, async (params) => {
  let provider = providerStore.getProvider(params.providerId) || providerStore.getActiveProvider();
  if ((!provider || params.baseUrl) && params.baseUrl) {
    provider = {
      id: params.providerId || "active",
      name: params.providerId || "Active Provider",
      baseUrl: params.baseUrl,
      apiKey: params.apiKey || (provider?.apiKey ?? ""),
      models: params.model
    };
  }
  if (!provider) {
    throw new Error(`Provider not configured for ID: ${params.providerId}`);
  }
  const systemPrompt = PromptBuilder.buildSystemPrompt({
    mode: params.mode || "CHAT",
    osInfo: params.osInfo || "Unknown OS",
    editorContext: params.editorContext
  });
  const normalizedMessages = PromptBuilder.normalizeMessagesForClaude(params.messages || [], systemPrompt);
  const fullResponse = await llmClient.streamChatCompletion(
    provider,
    {
      model: params.model,
      messages: normalizedMessages
    },
    {
      onToken: (token) => {
        server.sendNotification(RPC_METHODS.NOTIFY_CHAT_TOKEN, {
          sessionId: params.sessionId,
          token
        });
      },
      onComplete: () => {
        server.sendNotification(RPC_METHODS.NOTIFY_CHAT_COMPLETE, {
          sessionId: params.sessionId
        });
      },
      onError: (errMsg) => {
        server.sendNotification(RPC_METHODS.NOTIFY_CHAT_ERROR, {
          sessionId: params.sessionId,
          message: errMsg
        });
      }
    }
  );
  return { status: "ok", responseText: fullResponse };
});
server.start();
process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));
