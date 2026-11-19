package mg.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mg.Config;
import mg.check.FileContext;
import mg.util.Json;

/** OpenAI Chat Completions API (POST /v1/chat/completions), JSON 모드. */
public final class OpenAiClient implements LlmClient {

    private final Config cfg;

    public OpenAiClient(Config cfg) {
        this.cfg = cfg;
    }

    @Override
    public LlmResponse complete(String prompt, FileContext ctx) throws LlmException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "You are a careful security reviewer. Reply with one JSON object only."),
                Map.of("role", "user", "content", prompt)));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_completion_tokens", cfg.maxTokens);
        if (cfg.temperature != null) {
            body.put("temperature", cfg.temperature);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer " + cfg.apiKey);

        Map<String, Object> res = HttpLlm.postJson(cfg.openaiBaseUrl + "/v1/chat/completions", headers, body,
                cfg.timeoutSeconds, "OpenAI");
        List<Object> choices = Json.asList(res.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("OpenAI 응답에 choices가 없습니다.", false);
        }
        Map<String, Object> choice = Json.asMap(choices.get(0));
        Map<String, Object> message = Json.asMap(choice == null ? null : choice.get("message"));
        String text = message == null ? "" : String.valueOf(message.getOrDefault("content", ""));
        boolean truncated = choice != null && "length".equals(choice.get("finish_reason"));
        return new LlmResponse(text == null || text.equals("null") ? "" : text, truncated);
    }

    @Override
    public String label() {
        return "openai / " + cfg.model;
    }
}
