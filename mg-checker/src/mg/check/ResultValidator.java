package mg.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import mg.scan.Endpoint;
import mg.scan.PolicyParser.Policy;
import mg.util.Json;

/**
 * [규칙] LLM 응답을 검사해서 결과 카드로 바꾼다. 여기서 하는 일은 매번 같아야 하므로 LLM에 맡기지 않는다.
 * <ul>
 *   <li>JSON 형식 검사 (깨지면 FormatException)</li>
 *   <li>정책 목록에 없는 번호, 코드에 없는 API를 언급한 항목은 버린다</li>
 *   <li>근거 줄이 없거나 범위 밖인 "있음"은 "불확실"로 강등한다</li>
 *   <li>카드 상태(누락 의심 / 불확실 / 검사 있음)는 조건 판단으로 계산한다</li>
 * </ul>
 */
public final class ResultValidator {

    public record FileOutcome(List<Map<String, Object>> cards, List<String> notes, int downgrades) {
    }

    private ResultValidator() {
    }

    public static Map<String, Object> parseJson(String raw) throws FormatException {
        if (raw == null || raw.isBlank()) {
            throw new FormatException("응답이 비어 있음");
        }
        String t = raw.trim();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new FormatException("JSON 객체를 찾을 수 없음");
        }
        Object parsed;
        try {
            parsed = Json.parse(t.substring(a, b + 1));
        } catch (IllegalArgumentException e) {
            throw new FormatException("JSON 문법 오류: " + e.getMessage());
        }
        Map<String, Object> obj = Json.asMap(parsed);
        if (obj == null || Json.asList(obj.get("findings")) == null) {
            throw new FormatException("findings 배열이 없음");
        }
        return obj;
    }

    public static FileOutcome validate(String raw, FileContext ctx) throws FormatException {
        Map<String, Object> obj = parseJson(raw);
        List<Object> findings = Json.asList(obj.get("findings"));

        Map<String, Policy> policies = new HashMap<>();
        for (Policy p : ctx.policies()) {
            policies.put(p.id().toUpperCase(Locale.ROOT), p);
        }
        Map<String, Endpoint> endpoints = new HashMap<>();
        for (Endpoint e : ctx.endpoints()) {
            endpoints.putIfAbsent(Endpoint.normalizeKey(e.key()), e);
        }
        int lineCount = ctx.lines().size();

        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int downgrades = 0;

        for (Object o : findings) {
            Map<String, Object> f = Json.asMap(o);
            if (f == null) {
                notes.add("형식이 잘못된 항목 1개를 버림");
                continue;
            }
            String pid = normalizePolicyId(Json.str(f, "policy_id"));
            Policy policy = policies.get(pid);
            if (policy == null) {
                notes.add("정책 목록에 없는 번호(" + Json.str(f, "policy_id") + ")를 언급한 항목을 버림");
                continue;
            }
            String epText = Json.str(f, "endpoint");
            if (epText == null || epText.isBlank() || epText.equals("null")) {
                continue; // 이 파일에는 해당 API 없음
            }
            Endpoint ep = endpoints.get(Endpoint.normalizeKey(epText));
            if (ep == null) {
                notes.add("코드에 없는 API(" + epText + ")를 언급한 항목을 버림");
                continue;
            }
            String dupKey = pid + "|" + ep.key();
            if (!seen.add(dupKey)) {
                notes.add(pid + " ↔ " + ep.key() + " 가 두 번 나와 첫 번째만 남김");
                continue;
            }

            List<Map<String, Object>> conds = new ArrayList<>();
            List<Object> rawConds = Json.asList(f.get("conditions"));
            if (rawConds != null) {
                for (Object co : rawConds) {
                    Map<String, Object> c = Json.asMap(co);
                    if (c == null) {
                        continue;
                    }
                    String judgement = normalizeJudgement(Json.str(c, "judgement"));
                    Integer line = Json.intOrNull(c.get("evidence_line"));
                    boolean valid = line != null && line >= 1 && line <= lineCount;
                    String flag = null;
                    String fin = judgement;
                    if (judgement.equals("present") && !valid) {
                        fin = "uncertain";
                        flag = "근거 줄이 없거나 파일 범위 밖이라 「있음」을 「불확실」로 강등";
                        downgrades++;
                    } else if (judgement.equals("present") && (line < ep.start() - 3 || line > ep.end())) {
                        flag = "근거 줄(" + line + "행)이 이 메서드 밖에 있음. 클래스 수준 설정인지 확인";
                    } else if (!judgement.equals("present") && line != null && !valid) {
                        flag = "잘못된 줄번호(" + line + ")는 무시함";
                    }
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("condition", orDefault(Json.str(c, "condition"), "(조건 이름 없음)"));
                    cm.put("llm", judgement);
                    cm.put("final", fin);
                    cm.put("line", valid ? line : null);
                    cm.put("reason", orDefault(Json.str(c, "reason"), ""));
                    cm.put("flag", flag);
                    conds.add(cm);
                }
            }
            if (conds.isEmpty()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("condition", "(조건 없음)");
                cm.put("llm", "-");
                cm.put("final", "uncertain");
                cm.put("line", null);
                cm.put("reason", "LLM이 조건을 내지 않음");
                cm.put("flag", "조건이 비어 있어 「불확실」로 처리");
                conds.add(cm);
            }

            String status = "ok";
            for (Map<String, Object> c : conds) {
                if ("absent".equals(c.get("final"))) {
                    status = "missing";
                    break;
                }
                if ("uncertain".equals(c.get("final"))) {
                    status = "unsure";
                }
            }

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("file", ctx.path());
            card.put("policyId", policy.id());
            card.put("policyText", policy.text());
            card.put("endpoint", ep.key());
            card.put("start", ep.start());
            card.put("end", ep.end());
            card.put("status", status);
            card.put("conditions", conds);
            card.put("explanation", orDefault(Json.str(f, "explanation"), ""));
            card.put("fix", orDefault(Json.str(f, "fix"), ""));
            card.put("check", orDefault(Json.str(f, "check"), ""));
            cards.add(card);
        }
        return new FileOutcome(cards, notes, downgrades);
    }

    static String normalizePolicyId(String id) {
        if (id == null) {
            return "";
        }
        String t = id.trim().toUpperCase(Locale.ROOT);
        if (t.matches("\\d+")) {
            return "P" + t;
        }
        if (t.matches("R\\d+")) {
            return "P" + t.substring(1);
        }
        return t;
    }

    static String normalizeJudgement(String j) {
        if (j == null) {
            return "uncertain";
        }
        String t = j.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "present", "있음", "yes", "true" -> "present";
            case "absent", "없음", "no", "false", "missing" -> "absent";
            default -> "uncertain";
        };
    }

    private static String orDefault(String s, String def) {
        return s == null || s.equals("null") ? def : s.trim();
    }
}
