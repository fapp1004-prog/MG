package mg.llm;

import mg.Config;

public final class LlmClients {

    private LlmClients() {
    }

    public static LlmClient create(Config cfg) {
        return switch (cfg.provider) {
            case "openai" -> new OpenAiClient(cfg);
            case "mock" -> new MockClient();
            default -> new AnthropicClient(cfg);
        };
    }
}
