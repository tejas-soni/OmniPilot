import { RpcServer } from './rpc/server.js';
import { RPC_METHODS } from './rpc/protocol.js';
import { HistoryManager } from './history/manager.js';
import { ProviderStore } from './config/provider-store.js';
import { LlmClient } from './llm/client.js';
import { PromptBuilder } from './llm/prompt-builder.js';
import { ToolRegistry } from './agent/tool-registry.js';
import { ChatMessage } from './llm/models.js';



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
  baseUrl?: string;
  apiKey?: string;
  model: string;
  mode: any;
  osInfo: string;
  editorContext?: any;
}) => {
  let provider = providerStore.getProvider(params.providerId) || providerStore.getActiveProvider();

  if ((!provider || params.baseUrl) && params.baseUrl) {
    provider = {
      id: params.providerId || 'active',
      name: params.providerId || 'Active Provider',
      baseUrl: params.baseUrl,
      apiKey: params.apiKey || (provider?.apiKey ?? ''),
      models: params.model
    };
  }

  if (!provider) {
    throw new Error(`Provider not configured for ID: ${params.providerId}`);
  }

  const systemPrompt = PromptBuilder.buildSystemPrompt({
    mode: params.mode || 'CHAT',
    osInfo: params.osInfo || 'Unknown OS',
    editorContext: params.editorContext
  });

  const normalizedMessages: ChatMessage[] = PromptBuilder.normalizeMessagesForClaude(params.messages || [], systemPrompt);

  const mode = (params.mode || 'CHAT').toString().toUpperCase();
  const isAgent = mode === 'AGENT';
  const tools = isAgent ? ToolRegistry.getAgentTools() : undefined;

  const callbacks = {
    onToken: (token: string) => {
      server.sendNotification(RPC_METHODS.NOTIFY_CHAT_TOKEN, {
        sessionId: params.sessionId,
        token
      });
    },
    onComplete: () => { /* completion notification sent after the agent loop */ },
    onError: (errMsg: string) => {
      server.sendNotification(RPC_METHODS.NOTIFY_CHAT_ERROR, {
        sessionId: params.sessionId,
        message: errMsg
      });
    }
  };

  const MAX_AGENT_TURNS = 10;
  const conversation: ChatMessage[] = [...normalizedMessages];
  let fullResponse = '';

  for (let turn = 0; turn < MAX_AGENT_TURNS; turn++) {
    const result = await llmClient.streamChatCompletionWithTools(
      provider,
      {
        model: params.model,
        messages: conversation,
        ...(tools ? { tools } : {})
      },
      callbacks
    );

    fullResponse += result.text;

    // No tool calls (or not in agent mode) -> we are done.
    if (!isAgent || result.toolCalls.length === 0) {
      break;
    }

    // Append the assistant message containing the tool calls.
    const assistantMsg: ChatMessage = {
      role: 'assistant',
      content: result.text || null,
      tool_calls: result.toolCalls
    };
    conversation.push(assistantMsg);

    // Execute each tool call on the client and append the tool results.
    for (const call of result.toolCalls) {
      const toolName = call.function.name;
      const toolArgs = call.function.arguments;

      // Permission gate for tools that touch the workspace / shell.
      if (toolName === 'read_file' || toolName === 'write_file' || toolName === 'run_command') {

        try {
          const permission = await server.sendRequest<any>(RPC_METHODS.REQ_PERMISSION, {
            sessionId: params.sessionId,
            tool: toolName,
            arguments: toolArgs
          });
          const decision = typeof permission === 'string' ? permission : permission?.decision;
          if (decision === 'DENY' || decision === 'deny' || permission === false) {
            conversation.push({
              role: 'tool',
              name: toolName,
              content: 'Error: User denied permission to execute this tool.',
              tool_call_id: call.id
            });
            continue;
          }
        } catch {
          // If the client does not support permission requests, fail closed.
          conversation.push({
            role: 'tool',
            name: toolName,
            content: 'Error: Permission request not supported by client.',
            tool_call_id: call.id
          });
          continue;
        }
      }

      let toolResult: string;
      try {
        const res = await server.sendRequest<any>(RPC_METHODS.REQ_CHAT_TOOL_CALL, {
          sessionId: params.sessionId,
          id: call.id,
          name: toolName,
          arguments: toolArgs
        });
        toolResult = typeof res === 'string' ? res : (res?.result ?? JSON.stringify(res));
      } catch (e: any) {
        toolResult = `Error executing tool ${toolName}: ${e?.message ?? String(e)}`;
      }

      conversation.push({
        role: 'tool',
        name: toolName,
        content: toolResult,
        tool_call_id: call.id
      });
    }
    // Loop continues: invoke the model again with the tool results.
  }

  server.sendNotification(RPC_METHODS.NOTIFY_CHAT_COMPLETE, {
    sessionId: params.sessionId
  });

  return { status: 'ok', responseText: fullResponse };
});


server.start();

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
