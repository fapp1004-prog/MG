package mg.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mg.Config;
import mg.check.FileContext;
import mg.util.Json;

/** Anthropic Messages API (POST /v1/messages). */
public final class AnthropicClient implements LlmClient {

    private final Config cfg;

    public AnthropicClient(Config cfg) {
        this.cfg = cfg;
    }

    @Override
    public LlmResponse complete(String prompt, FileContext ctx) throws LlmException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model);
        body.put("max_tokens", cfg.maxTokens);
        if (cfg.temperature != null) {
            body.put("temperature", cfg.temperature);
        }
        body.put("system", "You are a careful security reviewer. Reply with one JSON object only.");
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", cfg.apiKey);
        headers.put("anthropic-version", "2023-06-01");

        Map<String, Object> res = HttpLlm.postJson(cfg.anthropicBaseUrl + "/v1/messages", headers, body,
                cfg.timeoutSeconds, "Anthropic");
        StringBuilder text = new StringBuilder();
        List<Object> content = Json.asList(res.get("content"));
        if (content != null) {
            for (Object o : content) {
                Map<String, Object> block = Json.asMap(o);
                if (block != null && "text".equals(block.get("type"))) {
                    text.append(Json.str(block, "text"));
                }
            }
        }
        boolean truncated = "max_tokens".equals(res.get("stop_reason"));
        return new LlmResponse(text.toString(), truncated);
    }

    @Override
    public String label() {
        return "anthropic / " + cfg.model;
    }
}
