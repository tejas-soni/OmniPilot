"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProviderStore = void 0;
class ProviderStore {
    providers = new Map();
    activeProviderId = '';
    setProviders(providers) {
        this.providers.clear();
        for (const p of providers) {
            this.providers.set(p.id, p);
        }
        if (providers.length > 0 && !this.activeProviderId) {
            this.activeProviderId = providers[0].id;
        }
    }
    setApiKey(providerId, apiKey) {
        const provider = this.providers.get(providerId);
        if (provider) {
            provider.apiKey = apiKey;
        }
    }
    getProvider(id) {
        return this.providers.get(id);
    }
    getActiveProvider() {
        return this.providers.get(this.activeProviderId);
    }
    setActiveProviderId(id) {
        if (this.providers.has(id)) {
            this.activeProviderId = id;
        }
    }
    getAllProviders() {
        return Array.from(this.providers.values());
    }
}
exports.ProviderStore = ProviderStore;
