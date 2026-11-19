package mg.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [규칙] 권한 정책 문서를 한 줄에 하나씩 정책으로 나눈다.
 * "P1. ...", "1) ...", "- R2. ..." 형식이면 번호를 쓰고, 아니면 순서대로 P1, P2...를 붙인다.
 * '#'으로 시작하는 제목 줄과 빈 줄은 건너뛴다.
 */
public final class PolicyParser {

    public record Policy(String id, String text) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("text", text);
            return m;
        }
    }

    private static final Pattern NUMBERED = Pattern.compile("^(?:[-*]\\s*)?\\**([PpRr]?\\d{1,3})\\**\\s*[.):]\\s*(.+)$");

    private PolicyParser() {
    }

    public static List<Policy> parse(String text) {
        List<Policy> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int n = 0;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String raw : text.replace("\r", "").split("\n")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(">") || t.startsWith("```")) {
                continue;
            }
            n++;
            Matcher m = NUMBERED.matcher(t);
            String id;
            String body;
            if (m.matches()) {
                String num = m.group(1).toUpperCase();
                id = Character.isDigit(num.charAt(0)) ? "P" + num : "P" + num.substring(1);
                body = m.group(2).trim();
            } else {
                id = "P" + n;
                body = t.replaceFirst("^[-*]\\s*", "");
            }
            while (used.contains(id)) {
                id = id + "'";
            }
            used.add(id);
            out.add(new Policy(id, body));
        }
        return out;
    }
}
