import { ChatCompletionRequest, ProviderConfig } from './models.js';
export interface LlmCallbacks {
    onToken: (token: string) => void;
    onComplete: () => void;
    onError: (error: string) => void;
}
export declare class LlmClient {
    private activeAbortController;
    cancelStream(): void;
    fetchModels(baseUrl: string, apiKey?: string): Promise<string[]>;
    streamChatCompletion(provider: ProviderConfig, requestPayload: ChatCompletionRequest, callbacks: LlmCallbacks): Promise<string>;
}
