package mg.check;

import java.util.List;
import java.util.Map;

import mg.util.Json;

/** 실행 결과를 마크다운 보고서로. 실행 폴더의 report.md로 저장되고, 화면에서 복사할 수 있다. */
public final class Report {

    private Report() {
    }

    private static String label(Object status) {
        return switch (String.valueOf(status)) {
            case "missing" -> "누락 의심";
            case "unsure" -> "불확실";
            case "ok" -> "검사 있음";
            case "nomatch" -> "짝 없는 API";
            case "nopair" -> "API 없는 정책";
            default -> String.valueOf(status);
        };
    }

    private static String judge(Object j) {
        return switch (String.valueOf(j)) {
            case "present" -> "있음";
            case "absent" -> "없음";
            case "uncertain" -> "불확실";
            default -> String.valueOf(j);
        };
    }

    public static String cardKey(Map<String, Object> c) {
        return c.get("policyId") + "|" + c.get("file") + "|" + c.get("endpoint");
    }

    public static String markdown(Map<String, Object> r, Map<String, Object> verdicts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MG 점검 보고서\n\n");
        sb.append("- 실행 ID: ").append(r.get("id")).append('\n');
        sb.append("- 시작: ").append(r.get("startedAt")).append(" / 종료: ").append(r.get("finishedAt")).append('\n');
        sb.append("- 프로젝트: ").append(r.get("root")).append('\n');
        sb.append("- 모델: ").append(r.get("model")).append('\n');
        Map<String, Object> counts = Json.asMap(r.get("counts"));
        sb.append("- 결과: API ").append(r.get("endpointCount")).append("개 · 정책 ")
                .append(Json.asList(r.get("policies")).size()).append("개 │ 누락 의심 ").append(counts.get("missing"))
                .append(" · 불확실 ").append(counts.get("unsure")).append(" · 검사 있음 ").append(counts.get("ok"))
                .append(" · 짝 없는 API ").append(counts.get("nomatch")).append(" · API 없는 정책 ").append(counts.get("nopair"))
                .append('\n');
        sb.append("- 규칙 처리: 강등 ").append(r.get("downgrades")).append("건, 형식 재요청 ").append(r.get("formatRetries")).append("건\n");

        long confirmed = verdicts.values().stream().filter("ok"::equals).count();
        long fp = verdicts.values().stream().filter("fp"::equals).count();
        sb.append("- 개발자 확인: 맞음 ").append(confirmed).append(" · 오탐 ").append(fp).append("\n\n");

        sb.append("## 결과 카드\n\n");
        sb.append("| 상태 | API | 정책 | 조건별 판단 (근거 줄) | 개발자 확인 |\n|---|---|---|---|---|\n");
        for (Object o : Json.asList(r.get("cards"))) {
            Map<String, Object> c = Json.asMap(o);
            StringBuilder conds = new StringBuilder();
            List<Object> cl = Json.asList(c.get("conditions"));
            if (cl != null) {
                for (Object co : cl) {
                    Map<String, Object> k = Json.asMap(co);
                    if (conds.length() > 0) {
                        conds.append("<br>");
                    }
                    conds.append(esc(String.valueOf(k.get("condition")))).append(": ").append(judge(k.get("final")));
                    if (k.get("line") != null) {
                        conds.append(" (").append(k.get("line")).append("행)");
                    }
                    if (!String.valueOf(k.get("llm")).equals(String.valueOf(k.get("final")))) {
                        conds.append(" ← LLM은 ").append(judge(k.get("llm")));
                    }
                }
            }
            Object v = verdicts.get(cardKey(c));
            String vt = v == null ? "" : "ok".equals(v) ? "맞음" : "fp".equals(v) ? "오탐" : String.valueOf(v);
            String api = c.get("endpoint") == null ? "-" : "`" + c.get("endpoint") + "`" + (c.get("file") == null ? "" : "<br>" + c.get("file"));
            String pol = c.get("policyId") == null ? "-" : c.get("policyId") + " " + esc(String.valueOf(c.get("policyText")));
            sb.append("| ").append(label(c.get("status"))).append(" | ").append(api).append(" | ").append(pol)
                    .append(" | ").append(conds).append(" | ").append(vt).append(" |\n");
        }

        sb.append("\n## 설명과 고칠 방향\n\n");
        for (Object o : Json.asList(r.get("cards"))) {
            Map<String, Object> c = Json.asMap(o);
            String st = String.valueOf(c.get("status"));
            if (!st.equals("missing") && !st.equals("unsure")) {
                continue;
            }
            sb.append("### [").append(label(st)).append("] ").append(c.get("endpoint")).append(" ↔ ").append(c.get("policyId")).append("\n\n");
            appendIf(sb, "설명", c.get("explanation"));
            appendIf(sb, "고칠 방향", c.get("fix"));
            appendIf(sb, "확인할 점", c.get("check"));
            sb.append('\n');
        }

        List<Object> notes = Json.asList(r.get("notes"));
        if (notes != null && !notes.isEmpty()) {
            sb.append("## 규칙 처리 메모\n\n");
            for (Object n : notes) {
                sb.append("- ").append(n).append('\n');
            }
        }
        sb.append("\n> LLM 응답 원문은 이 실행 폴더의 raw/, 보낸 지시문은 prompts/ 에 있습니다.\n");
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, String label, Object v) {
        if (v != null && !String.valueOf(v).isBlank()) {
            sb.append("- **").append(label).append("**: ").append(v).append('\n');
        }
    }

    private static String esc(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
