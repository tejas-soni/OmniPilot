"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LlmClient = void 0;
class LlmClient {
    activeAbortController = null;
    cancelStream() {
        if (this.activeAbortController) {
            this.activeAbortController.abort();
            this.activeAbortController = null;
        }
    }
    async fetchModels(baseUrl, apiKey) {
        const cleanUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
        const targetUrl = `${cleanUrl}/models`;
        const headers = {
            'Accept': 'application/json'
        };
        if (apiKey && apiKey.trim().length > 0) {
            headers['Authorization'] = `Bearer ${apiKey.trim()}`;
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
        const cleanUrl = provider.baseUrl.endsWith('/') ? provider.baseUrl.slice(0, -1) : provider.baseUrl;
        const targetUrl = `${cleanUrl}/chat/completions`;
        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream'
        };
        if (provider.apiKey && provider.apiKey.trim().length > 0) {
            headers['Authorization'] = `Bearer ${provider.apiKey.trim()}`;
        }
        let fullText = '';
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
                return '';
            }
            if (!response.body) {
                callbacks.onError('Response body is null');
                return '';
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            while (true) {
                const { done, value } = await reader.read();
                if (done)
                    break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop() || '';
                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || trimmed.startsWith(':'))
                        continue;
                    if (trimmed.startsWith('data: ')) {
                        const dataStr = trimmed.slice(6).trim();
                        if (dataStr === '[DONE]') {
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
                        }
                        catch (e) {
                            // Ignore non-JSON chunks
                        }
                    }
                }
            }
            callbacks.onComplete();
            return fullText;
        }
        catch (e) {
            if (e.name === 'AbortError') {
                callbacks.onComplete();
            }
            else {
                callbacks.onError(e.message || 'Network stream error');
            }
            return fullText;
        }
        finally {
            this.activeAbortController = null;
        }
    }
}
exports.LlmClient = LlmClient;
