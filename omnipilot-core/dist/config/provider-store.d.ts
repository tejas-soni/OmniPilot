import { ProviderConfig } from '../llm/models.js';
export declare class ProviderStore {
    private providers;
    private activeProviderId;
    setProviders(providers: ProviderConfig[]): void;
    setApiKey(providerId: string, apiKey: string): void;
    getProvider(id: string): ProviderConfig | undefined;
    getActiveProvider(): ProviderConfig | undefined;
    setActiveProviderId(id: string): void;
    getAllProviders(): ProviderConfig[];
}
