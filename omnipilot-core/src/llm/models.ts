export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content?: string | null;
  name?: string | null;
  tool_calls?: ToolCall[] | null;
  tool_call_id?: string | null;
}

export interface ToolCall {
  id: string;
  type: 'function';
  function: {
    name: string;
    arguments: string;
  };
}

export interface Tool {
  type: 'function';
  function: {
    name: string;
    description: string;
    parameters: Record<string, any>;
  };
}

export interface ChatCompletionRequest {
  model: string;
  messages: ChatMessage[];
  tools?: Tool[];
  tool_choice?: string | Record<string, any>;
  stream?: boolean;
  temperature?: number;
}

export interface ChatCompletionChunk {
  id: string;
  object: string;
  created: number;
  model: string;
  choices: ChunkChoice[];
}

export interface ChunkChoice {
  index: number;
  delta: Delta;
  finish_reason?: string | null;
}

export interface Delta {
  role?: string;
  content?: string | null;
  tool_calls?: DeltaToolCall[];
}

export interface DeltaToolCall {
  index: number;
  id?: string;
  type?: string;
  function?: {
    name?: string;
    arguments?: string;
  };
}

export interface ProviderConfig {
  id: string;
  name: string;
  baseUrl: string;
  models: string;
  apiKey?: string;
}
