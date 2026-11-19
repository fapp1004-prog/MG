package mg.scan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러 안의 API 하나. start, end는 1부터 세는 줄번호로, 어노테이션 줄부터 메서드의 닫는 중괄호까지다.
 *
 * @param key    "GET /api/bookings/{id}" 형태
 * @param method HTTP 메서드 (어노테이션에 없으면 ANY)
 * @param path   클래스 경로를 합친 전체 경로
 */
public record Endpoint(String key, String method, String path, int start, int end, String methodName) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("method", method);
        m.put("path", path);
        m.put("start", start);
        m.put("end", end);
        m.put("methodName", methodName);
        return m;
    }

    /** 비교용 정규화: 메서드 대문자, 공백 하나, 끝 슬래시 제거, 경로 변수 이름은 무시. */
    public static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim().replaceAll("\\s+", " ");
        int sp = k.indexOf(' ');
        if (sp < 0) {
            return k;
        }
        String method = k.substring(0, sp).toUpperCase();
        String path = k.substring(sp + 1).trim();
        path = path.replaceAll("\\{[^}]*}", "{}");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return method + " " + path;
    }
}
