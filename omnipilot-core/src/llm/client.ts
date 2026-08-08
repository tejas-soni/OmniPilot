import { ChatCompletionRequest, ChatCompletionChunk, ProviderConfig, ToolCall } from './models.js';

export interface LlmCallbacks {
  onToken: (token: string) => void;
  onComplete: () => void;
  onError: (error: string) => void;
}

export interface StreamResult {
  text: string;
  toolCalls: ToolCall[];
}

interface PendingToolCall {
  id?: string;
  name?: string;
  args: string;
}

/**
 * Returns the first balanced JSON object found in `raw`. Some models emit
 * multiple concatenated JSON objects ("{...}{...}") in a single tool call's
 * arguments; downstream JSON parsers reject that with "Extra data". Walks the
 * string tracking brace depth (ignoring braces inside string literals) and
 * returns the substring for the first complete object. If no balanced object
 * is found, the original string is returned unchanged.
 */
/**
 * Parses tool calls that a model emitted as plain text in the content stream,
 * e.g. `<function/run_command>{"command": "..."}` or `<function=read_file>{...}`
 * or `<tool_name>{...}`. Returns the extracted tool calls plus the text with
 * those tool-call fragments removed. Used as a fallback when the provider/model
 * does not return native OpenAI `tool_calls`.
 */
export function parseTextToolCalls(text: string): { toolCalls: ToolCall[]; cleanedText: string } {
  const toolCalls: ToolCall[] = [];
  let cleaned = text;
  // Tolerate many separators the model might use between "function" and the name:
  //   <function/NAME>{...}  <function=NAME>{...}  <function NAME>{...}  <function(NAME){...}  <function(NAME) {...}
  const re = /<function[\s\/=\(]+\s*([A-Za-z0-9_\-]+)\s*\)?\s*>?\s*(\{[\s\S]*?\})\s*(?:<\/function>|(?=<function)|$)/g;

  let m: RegExpExecArray | null;
  let idx = 0;
  while ((m = re.exec(text)) !== null) {
    const name = m[1];
    const args = extractFirstJsonObject(m[2]);
    toolCalls.push({
      id: `textcall_${idx++}`,
      type: 'function',
      function: { name, arguments: args }
    });
  }
  if (toolCalls.length > 0) {
    // Remove all tool-call fragments from the visible text.
    cleaned = text.replace(/<function[\s\/=\(]+\s*[A-Za-z0-9_\-]+\s*\)?\s*>?\s*\{[\s\S]*?\}\s*(<\/function>)?/g, '').trim();

  }
  return { toolCalls, cleanedText: cleaned };
}


export function extractFirstJsonObject(raw: string): string {

  const start = raw.indexOf('{');
  if (start === -1) return raw;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < raw.length; i++) {
    const c = raw[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (c === '\\') {
        escaped = true;
      } else if (c === '"') {
        inString = false;
      }
    } else {
      if (c === '"') inString = true;
      else if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) return raw.substring(start, i + 1);
      }
    }
  }
  return raw;
}


export class LlmClient {
  private activeAbortController: AbortController | null = null;

  public cancelStream(): void {
    if (this.activeAbortController) {
      this.activeAbortController.abort();
      this.activeAbortController = null;
    }
  }

  public async fetchModels(baseUrl: string, apiKey?: string): Promise<string[]> {
    const cleanUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const targetUrl = `${cleanUrl}/models`;

    const headers: Record<string, string> = {
      'Accept': 'application/json'
    };

    if (apiKey && apiKey.trim().length > 0) {
      headers['Authorization'] = `Bearer ${apiKey.trim()}`;
    }

    const response = await fetch(targetUrl, { headers });
    if (!response.ok) {
      throw new Error(`Failed to fetch models: HTTP ${response.status} ${response.statusText}`);
    }

    const json = await response.json() as any;
    if (json && Array.isArray(json.data)) {
      return json.data.map((m: any) => m.id || m.name).filter(Boolean);
    }

    return [];
  }

  public async streamChatCompletion(
    provider: ProviderConfig,
    requestPayload: ChatCompletionRequest,
    callbacks: LlmCallbacks
  ): Promise<string> {
    const result = await this.streamChatCompletionWithTools(provider, requestPayload, callbacks);
    return result.text;
  }

  /**
   * Streams a chat completion and returns both the accumulated assistant text
   * and any tool calls the model requested. Tool-call fragments are accumulated
   * per `index` so parallel tool calls are not concatenated together.
   */
  public async streamChatCompletionWithTools(
    provider: ProviderConfig,
    requestPayload: ChatCompletionRequest,
    callbacks: LlmCallbacks
  ): Promise<StreamResult> {
    this.cancelStream();
    this.activeAbortController = new AbortController();
    const signal = this.activeAbortController.signal;

    const cleanUrl = provider.baseUrl.endsWith('/') ? provider.baseUrl.slice(0, -1) : provider.baseUrl;
    const targetUrl = `${cleanUrl}/chat/completions`;

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    };

    if (provider.apiKey && provider.apiKey.trim().length > 0) {
      headers['Authorization'] = `Bearer ${provider.apiKey.trim()}`;
    }

    let fullText = '';
    const pendingToolCalls = new Map<number, PendingToolCall>();

    const buildResult = (): StreamResult => {
      const toolCalls: ToolCall[] = [];
      for (const [idx, p] of [...pendingToolCalls.entries()].sort((a, b) => a[0] - b[0])) {
        if (!p.name) continue;
        toolCalls.push({
          id: p.id ?? `call_${idx}`,
          type: 'function',
          function: {
            name: p.name,
            arguments: extractFirstJsonObject(p.args)
          }
        });
      }
      // Fallback: if the model emitted tool calls as text (no native tool_calls),
      // extract them from the content and strip them from the visible text.
      if (toolCalls.length === 0 && fullText.includes('<function')) {
        const parsed = parseTextToolCalls(fullText);
        if (parsed.toolCalls.length > 0) {
          return { text: parsed.cleanedText, toolCalls: parsed.toolCalls };
        }
      }
      return { text: fullText, toolCalls };
    };


    try {
      const response = await fetch(targetUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify({ ...requestPayload, stream: true }),
        signal
      });

      if (!response.ok) {
        const errBody = await response.text().catch(() => '');
        const errorMsg = `HTTP ${response.status} ${response.statusText}: ${errBody}`;
        callbacks.onError(errorMsg);
        return { text: '', toolCalls: [] };
      }

      if (!response.body) {
        callbacks.onError('Response body is null');
        return { text: '', toolCalls: [] };
      }

      const reader = (response.body as any).getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;

          if (trimmed.startsWith('data: ')) {
            const dataStr = trimmed.slice(6).trim();
            if (dataStr === '[DONE]') {
              callbacks.onComplete();
              return buildResult();
            }

            try {
              const chunk: ChatCompletionChunk = JSON.parse(dataStr);
              const delta = chunk.choices?.[0]?.delta;

              const deltaToolCalls = delta?.tool_calls;
              if (Array.isArray(deltaToolCalls) && deltaToolCalls.length > 0) {
                for (const tc of deltaToolCalls) {
                  const idx = tc.index ?? 0;
                  let pending = pendingToolCalls.get(idx);
                  if (!pending) {
                    pending = { args: '' };
                    pendingToolCalls.set(idx, pending);
                  }
                  if (tc.id) pending.id = tc.id;
                  if (tc.function?.name) pending.name = tc.function.name;
                  if (tc.function?.arguments) pending.args += tc.function.arguments;
                }
                continue;
              }

              const deltaContent = delta?.content;
              if (deltaContent) {
                fullText += deltaContent;
                callbacks.onToken(deltaContent);
              }
            } catch (e) {
              // Ignore non-JSON chunks
            }
          }
        }
      }

      callbacks.onComplete();
      return buildResult();
    } catch (e: any) {
      if (e.name === 'AbortError') {
        callbacks.onComplete();
      } else {
        callbacks.onError(e.message || 'Network stream error');
      }
      return buildResult();
    } finally {
      this.activeAbortController = null;
    }
  }
}

