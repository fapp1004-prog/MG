package mg.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import mg.util.Json;

/** 두 공급자가 같이 쓰는 HTTP 호출. 일시적 오류(429, 5xx)는 두 번까지 다시 시도한다. */
final class HttpLlm {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private HttpLlm() {
    }

    static Map<String, Object> postJson(String url, Map<String, String> headers, Object body,
                                        int timeoutSeconds, String providerName) throws LlmException {
        String payload = Json.stringify(body);
        int attempt = 0;
        while (true) {
            attempt++;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            HttpResponse<String> res;
            try {
                res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt <= 2) {
                    sleep(1500L * attempt);
                    continue;
                }
                throw new LlmException(providerName + " 연결 실패: " + e.getMessage()
                        + " (인터넷 연결, 방화벽, 프록시 설정을 확인하세요)", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("호출이 중단되었습니다.", true);
            }
            int code = res.statusCode();
            if (code >= 200 && code < 300) {
                try {
                    Map<String, Object> m = Json.asMap(Json.parse(res.body()));
                    if (m == null) {
                        throw new LlmException(providerName + " 응답이 JSON 객체가 아닙니다.", false);
                    }
                    return m;
                } catch (IllegalArgumentException e) {
                    throw new LlmException(providerName + " 응답을 읽을 수 없습니다: " + e.getMessage(), false);
                }
            }
            boolean retryable = code == 429 || code == 408 || code >= 500;
            if (retryable && attempt <= 2) {
                long wait = 2000L * attempt;
                String ra = res.headers().firstValue("retry-after").orElse(null);
                if (ra != null && ra.matches("\\d{1,3}")) {
                    wait = Math.min(30, Integer.parseInt(ra)) * 1000L;
                }
                sleep(wait);
                continue;
            }
            String msg = errorMessage(res.body());
            boolean fatal = code == 401 || code == 403 || code == 404;
            String hint = switch (code) {
                case 401 -> " → API 키가 틀렸거나 만료되었습니다. mg.properties의 api.key를 확인하세요.";
                case 403 -> " → 이 키로 해당 모델을 쓸 권한이 없습니다.";
                case 404 -> " → 모델 이름이 틀렸을 수 있습니다. mg.properties의 model을 확인하세요.";
                case 429 -> " → 사용 한도에 걸렸습니다. 잠시 뒤 다시 시도하세요.";
                case 400 -> " → 요청 형식 오류입니다. 코드가 너무 길면 컨트롤러를 나눠서 점검하세요.";
                default -> "";
            };
            throw new LlmException(providerName + " API 오류 (" + code + "): " + msg + hint, fatal);
        }
    }

    private static String errorMessage(String body) {
        try {
            Map<String, Object> m = Json.asMap(Json.parse(body));
            Map<String, Object> err = Json.asMap(m == null ? null : m.get("error"));
            if (err != null && err.get("message") != null) {
                return String.valueOf(err.get("message"));
            }
        } catch (IllegalArgumentException ignored) {
            // 본문이 JSON이 아님
        }
        return body == null ? "" : body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
