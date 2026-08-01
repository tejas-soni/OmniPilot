"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const server_js_1 = require("./rpc/server.js");
const protocol_js_1 = require("./rpc/protocol.js");
const manager_js_1 = require("./history/manager.js");
const provider_store_js_1 = require("./config/provider-store.js");
const client_js_1 = require("./llm/client.js");
const prompt_builder_js_1 = require("./llm/prompt-builder.js");
const server = new server_js_1.RpcServer(process.stdin, process.stdout);
const historyManager = new manager_js_1.HistoryManager();
const providerStore = new provider_store_js_1.ProviderStore();
const llmClient = new client_js_1.LlmClient();
// History Handlers
server.registerHandler(protocol_js_1.RPC_METHODS.HISTORY_LIST, () => {
    return historyManager.getSessions();
});
server.registerHandler(protocol_js_1.RPC_METHODS.HISTORY_LOAD, (params) => {
    return historyManager.getSession(params?.id);
});
server.registerHandler(protocol_js_1.RPC_METHODS.HISTORY_SAVE, (params) => {
    if (params?.session) {
        historyManager.saveSession(params.session);
    }
    return { status: 'ok' };
});
server.registerHandler(protocol_js_1.RPC_METHODS.HISTORY_DELETE, (params) => {
    if (params?.id) {
        historyManager.deleteSession(params.id);
    }
    return { status: 'ok' };
});
server.registerHandler(protocol_js_1.RPC_METHODS.HISTORY_CLEAR, () => {
    historyManager.clearAll();
    return { status: 'ok' };
});
// Config Handlers
server.registerHandler(protocol_js_1.RPC_METHODS.CONFIG_SET_PROVIDERS, (params) => {
    if (Array.isArray(params?.providers)) {
        providerStore.setProviders(params.providers);
    }
    return { status: 'ok' };
});
server.registerHandler(protocol_js_1.RPC_METHODS.CONFIG_SET_API_KEY, (params) => {
    if (params?.providerId) {
        providerStore.setApiKey(params.providerId, params.apiKey);
    }
    return { status: 'ok' };
});
// Models Handler
server.registerHandler(protocol_js_1.RPC_METHODS.MODELS_FETCH, async (params) => {
    if (!params?.baseUrl) {
        throw new Error('baseUrl is required');
    }
    return await llmClient.fetchModels(params.baseUrl, params.apiKey);
});
// Chat Handlers
server.registerHandler(protocol_js_1.RPC_METHODS.CHAT_CANCEL, () => {
    llmClient.cancelStream();
    return { status: 'ok' };
});
server.registerHandler(protocol_js_1.RPC_METHODS.CHAT_SEND, async (params) => {
    const provider = providerStore.getProvider(params.providerId) || providerStore.getActiveProvider();
    if (!provider) {
        throw new Error(`Provider not configured for ID: ${params.providerId}`);
    }
    const systemPrompt = prompt_builder_js_1.PromptBuilder.buildSystemPrompt({
        mode: params.mode || 'CHAT',
        osInfo: params.osInfo || 'Unknown OS',
        editorContext: params.editorContext
    });
    const normalizedMessages = prompt_builder_js_1.PromptBuilder.normalizeMessagesForClaude(params.messages || [], systemPrompt);
    const fullResponse = await llmClient.streamChatCompletion(provider, {
        model: params.model,
        messages: normalizedMessages
    }, {
        onToken: (token) => {
            server.sendNotification(protocol_js_1.RPC_METHODS.NOTIFY_CHAT_TOKEN, {
                sessionId: params.sessionId,
                token
            });
        },
        onComplete: () => {
            server.sendNotification(protocol_js_1.RPC_METHODS.NOTIFY_CHAT_COMPLETE, {
                sessionId: params.sessionId
            });
        },
        onError: (errMsg) => {
            server.sendNotification(protocol_js_1.RPC_METHODS.NOTIFY_CHAT_ERROR, {
                sessionId: params.sessionId,
                message: errMsg
            });
        }
    });
    return { status: 'ok', responseText: fullResponse };
});
server.start();
process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
