import { RpcServer } from './rpc/server.js';
import { RPC_METHODS } from './rpc/protocol.js';
import { HistoryManager } from './history/manager.js';
import { ProviderStore } from './config/provider-store.js';
import { LlmClient } from './llm/client.js';
import { PromptBuilder } from './llm/prompt-builder.js';

const server = new RpcServer(process.stdin, process.stdout);
const historyManager = new HistoryManager();
const providerStore = new ProviderStore();
const llmClient = new LlmClient();

// History Handlers
server.registerHandler(RPC_METHODS.HISTORY_LIST, () => {
  return historyManager.getSessions();
});

server.registerHandler(RPC_METHODS.HISTORY_LOAD, (params: { id: string }) => {
  return historyManager.getSession(params?.id);
});

server.registerHandler(RPC_METHODS.HISTORY_SAVE, (params: { session: any }) => {
  if (params?.session) {
    historyManager.saveSession(params.session);
  }
  return { status: 'ok' };
});

server.registerHandler(RPC_METHODS.HISTORY_DELETE, (params: { id: string }) => {
  if (params?.id) {
    historyManager.deleteSession(params.id);
  }
  return { status: 'ok' };
});

server.registerHandler(RPC_METHODS.HISTORY_CLEAR, () => {
  historyManager.clearAll();
  return { status: 'ok' };
});

// Config Handlers
server.registerHandler(RPC_METHODS.CONFIG_SET_PROVIDERS, (params: { providers: any[] }) => {
  if (Array.isArray(params?.providers)) {
    providerStore.setProviders(params.providers);
  }
  return { status: 'ok' };
});

server.registerHandler(RPC_METHODS.CONFIG_SET_API_KEY, (params: { providerId: string; apiKey: string }) => {
  if (params?.providerId) {
    providerStore.setApiKey(params.providerId, params.apiKey);
  }
  return { status: 'ok' };
});

// Models Handler
server.registerHandler(RPC_METHODS.MODELS_FETCH, async (params: { baseUrl: string; apiKey?: string }) => {
  if (!params?.baseUrl) {
    throw new Error('baseUrl is required');
  }
  return await llmClient.fetchModels(params.baseUrl, params.apiKey);
});

// Chat Handlers
server.registerHandler(RPC_METHODS.CHAT_CANCEL, () => {
  llmClient.cancelStream();
  return { status: 'ok' };
});

server.registerHandler(RPC_METHODS.CHAT_SEND, async (params: {
  sessionId: string;
  messages: any[];
  providerId: string;
  model: string;
  mode: any;
  osInfo: string;
  editorContext?: any;
}) => {
  const provider = providerStore.getProvider(params.providerId) || providerStore.getActiveProvider();
  if (!provider) {
    throw new Error(`Provider not configured for ID: ${params.providerId}`);
  }

  const systemPrompt = PromptBuilder.buildSystemPrompt({
    mode: params.mode || 'CHAT',
    osInfo: params.osInfo || 'Unknown OS',
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
      onToken: (token: string) => {
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
      onError: (errMsg: string) => {
        server.sendNotification(RPC_METHODS.NOTIFY_CHAT_ERROR, {
          sessionId: params.sessionId,
          message: errMsg
        });
      }
    }
  );

  return { status: 'ok', responseText: fullResponse };
});

server.start();

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
