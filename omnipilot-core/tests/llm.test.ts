import { PromptBuilder } from '../src/llm/prompt-builder.js';
import { ProviderStore } from '../src/config/provider-store.js';
import { LlmClient } from '../src/llm/client.js';

describe('LLM & Prompt Unit Tests', () => {
  test('should build READ-ONLY system prompt', () => {
    const prompt = PromptBuilder.buildSystemPrompt({
      mode: 'READ-ONLY',
      osInfo: 'Windows 11',
      editorContext: { file: 'index.ts', content: 'const x = 1;', language: 'typescript' }
    });

    expect(prompt).toContain('Mode: READ-ONLY.');
    expect(prompt).toContain('index.ts');
    expect(prompt).toContain('const x = 1;');
  });

  test('should build CHAT mode system prompt', () => {
    const prompt = PromptBuilder.buildSystemPrompt({
      mode: 'CHAT',
      osInfo: 'macOS 14',
      editorContext: { file: 'app.py', selectedText: 'print("hello")' }
    });

    expect(prompt).toContain('Mode: CHAT.');
    expect(prompt).toContain('print("hello")');
  });

  test('should build AGENT mode system prompt', () => {
    const prompt = PromptBuilder.buildSystemPrompt({
      mode: 'AGENT',
      osInfo: 'Linux x64',
      editorContext: { selectedText: 'console.log("hello");' }
    });

    expect(prompt).toContain('Mode: AGENT.');
    expect(prompt).toContain('console.log("hello");');
  });

  test('should normalize system prompts for Claude', () => {
    const messages = PromptBuilder.normalizeMessagesForClaude(
      [{ role: 'user', content: 'Hi' }],
      'System context'
    );

    expect(messages[0].role).toBe('system');
    expect(messages[0].content).toBe('System context');

    const withExistingSystem = PromptBuilder.normalizeMessagesForClaude(
      [{ role: 'system', content: 'Existing' }, { role: 'user', content: 'Hi' }],
      'System context'
    );
    expect(withExistingSystem[0].content).toBe('System context\nExisting');
  });

  test('ProviderStore should manage provider state', () => {
    const store = new ProviderStore();
    store.setProviders([
      { id: 'p1', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', models: 'gpt-4' },
      { id: 'p2', name: 'Anthropic', baseUrl: 'https://api.anthropic.com', models: 'claude-3' }
    ]);

    expect(store.getActiveProvider()?.id).toBe('p1');
    store.setActiveProviderId('p2');
    expect(store.getActiveProvider()?.id).toBe('p2');

    store.setApiKey('p1', 'sk-test');
    expect(store.getProvider('p1')?.apiKey).toBe('sk-test');
    expect(store.getAllProviders()).toHaveLength(2);
  });

  test('LlmClient fetchModels success', async () => {
    const client = new LlmClient();
    const mockFetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: [{ id: 'gpt-4' }, { name: 'gpt-3.5-turbo' }] })
    });
    global.fetch = mockFetch as any;

    const models = await client.fetchModels('https://api.openai.com/v1/', 'sk-key');
    expect(models).toEqual(['gpt-4', 'gpt-3.5-turbo']);
  });

  test('LlmClient fetchModels error handling', async () => {
    const client = new LlmClient();
    const mockFetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized'
    });
    global.fetch = mockFetch as any;

    await expect(client.fetchModels('https://api.openai.com/v1')).rejects.toThrow('HTTP 401');

    const mockFetchInvalidJson = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ invalid: true })
    });
    global.fetch = mockFetchInvalidJson as any;
    const result = await client.fetchModels('https://api.openai.com/v1');
    expect(result).toEqual([]);
  });

  test('LlmClient streamChatCompletion error responses', async () => {
    const client = new LlmClient();
    const mockFetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Error',
      text: async () => 'Server error body'
    });
    global.fetch = mockFetch as any;

    const onError = jest.fn();
    await client.streamChatCompletion(
      { id: 'p1', name: 'OpenAI', baseUrl: 'http://localhost', models: 'm1', apiKey: 'key' },
      { model: 'm1', messages: [] },
      { onToken: jest.fn(), onComplete: jest.fn(), onError }
    );

    expect(onError).toHaveBeenCalledWith(expect.stringContaining('HTTP 500'));
  });

  test('LlmClient streamChatCompletion null body', async () => {
    const client = new LlmClient();
    const mockFetch = jest.fn().mockResolvedValue({
      ok: true,
      body: null
    });
    global.fetch = mockFetch as any;

    const onError = jest.fn();
    await client.streamChatCompletion(
      { id: 'p1', name: 'OpenAI', baseUrl: 'http://localhost', models: 'm1' },
      { model: 'm1', messages: [] },
      { onToken: jest.fn(), onComplete: jest.fn(), onError }
    );

    expect(onError).toHaveBeenCalledWith('Response body is null');
  });

  test('LlmClient streamChatCompletion network throw', async () => {
    const client = new LlmClient();
    const mockFetch = jest.fn().mockRejectedValue(new Error('Network error'));
    global.fetch = mockFetch as any;

    const onError = jest.fn();
    await client.streamChatCompletion(
      { id: 'p1', name: 'OpenAI', baseUrl: 'http://localhost', models: 'm1' },
      { model: 'm1', messages: [] },
      { onToken: jest.fn(), onComplete: jest.fn(), onError }
    );

    expect(onError).toHaveBeenCalledWith('Network error');
  });

  test('LlmClient streamChatCompletion successful streaming', async () => {
    const client = new LlmClient();
    const chunks = [
      'data: {"choices":[{"delta":{"content":"Hello"}}]}\n\n',
      'data: {"choices":[{"delta":{"content":" world"}}]}\n\n',
      'data: [DONE]\n\n'
    ];
    let chunkIdx = 0;

    const mockStream = {
      getReader: () => ({
        read: async () => {
          if (chunkIdx < chunks.length) {
            const encoder = new TextEncoder();
            const val = encoder.encode(chunks[chunkIdx++]);
            return { done: false, value: val };
          }
          return { done: true, value: undefined };
        }
      })
    };

    const mockFetch = jest.fn().mockResolvedValue({
      ok: true,
      body: mockStream
    });
    global.fetch = mockFetch as any;

    const onToken = jest.fn();
    const onComplete = jest.fn();
    const res = await client.streamChatCompletion(
      { id: 'p1', name: 'OpenAI', baseUrl: 'http://localhost', models: 'm1' },
      { model: 'm1', messages: [] },
      { onToken, onComplete, onError: jest.fn() }
    );

    expect(res).toBe('Hello world');
    expect(onToken).toHaveBeenCalledTimes(2);
    expect(onComplete).toHaveBeenCalled();
  });
});
