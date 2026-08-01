import { ProviderConfig } from '../llm/models.js';

export class ProviderStore {
  private providers: Map<string, ProviderConfig> = new Map();
  private activeProviderId: string = '';

  public setProviders(providers: ProviderConfig[]): void {
    this.providers.clear();
    for (const p of providers) {
      this.providers.set(p.id, p);
    }
    if (providers.length > 0 && !this.activeProviderId) {
      this.activeProviderId = providers[0].id;
    }
  }

  public setApiKey(providerId: string, apiKey: string): void {
    const provider = this.providers.get(providerId);
    if (provider) {
      provider.apiKey = apiKey;
    }
  }

  public getProvider(id: string): ProviderConfig | undefined {
    return this.providers.get(id);
  }

  public getActiveProvider(): ProviderConfig | undefined {
    return this.providers.get(this.activeProviderId);
  }

  public setActiveProviderId(id: string): void {
    if (this.providers.has(id)) {
      this.activeProviderId = id;
    }
  }

  public getAllProviders(): ProviderConfig[] {
    return Array.from(this.providers.values());
  }
}
