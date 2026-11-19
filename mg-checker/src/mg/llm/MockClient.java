package mg.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import mg.check.FileContext;
import mg.scan.Endpoint;
import mg.scan.PolicyParser.Policy;
import mg.util.Json;

/**
 * API 키 없이 화면과 흐름을 확인하기 위한 데모용 흉내. 실제 LLM이 아니라 키워드 규칙으로 판단하므로
 * 변수명이 다른 코드에서는 틀린다. (그 차이가 바로 LLM이 필요한 이유다.)
 * demo-ticket 프로젝트의 정책 문서 표현에 맞춰져 있다.
 */
public final class MockClient implements LlmClient {

    private static final Pattern AUTH_PRINCIPAL = Pattern.compile("@AuthenticationPrincipal");
    private static final Pattern OWNER = Pattern.compile(
            "getUserId\\(\\)\\s*\\.equals\\(\\s*\\w+\\.getId\\(\\)\\s*\\)|\\w+\\.getId\\(\\)\\s*\\.equals\\(\\s*\\w+\\.getUserId\\(\\)\\s*\\)|findByIdAndUserId");
    private static final Pattern DELEGATE = Pattern.compile("\\w*[Ss]ervice\\.\\w+\\([^)]*\\buser\\b");
    private static final Pattern MY_LIST = Pattern.compile("findByUserId\\(\\s*\\w+\\.getId\\(\\)\\s*\\)");
    private static final Pattern CLIENT_PRICE = Pattern.compile("req\\w*\\.getPrice\\(\\)");
    private static final Pattern SERVER_PRICE = Pattern.compile("seat\\.getPrice\\(\\)");
    private static final Pattern ADMIN = Pattern.compile("hasRole\\(\\s*'ADMIN'\\s*\\)|hasRole\\(\\s*\\\\?\"ADMIN|@Secured|isAdmin\\(\\)|Role\\.ADMIN");

    @Override
    public LlmResponse complete(String prompt, FileContext ctx) {
        List<Object> findings = new ArrayList<>();
        for (Policy p : ctx.policies()) {
            String t = p.text();
            Endpoint ep;
            if (t.contains("상세")) {
                ep = find(ctx, "GET", "/bookings/{}", true);
                if (ep != null) {
                    findings.add(owner(ctx, p, ep, false,
                            "경로의 id만 바꾸면 다른 사용자의 예매 내역이 반환될 수 있다. 로그인 사용자를 받기만 하고 비교에 쓰지 않는다.",
                            "booking.getUserId()와 user.getId()가 다르면 403을 반환하거나 findByIdAndUserId로 조회한다."));
                }
            } else if (t.contains("목록")) {
                ep = find(ctx, "GET", "/bookings", true);
                if (ep != null) {
                    int ln = line(ctx, ep, MY_LIST);
                    findings.add(finding(p, ep, List.of(
                                    cond("로그인 사용자 기준으로만 조회하는가", ln > 0 ? "present" : "absent", ln > 0 ? ln : null,
                                            ln > 0 ? "로그인 사용자 id로 조회 범위를 제한함" : "조회 조건에 로그인 사용자가 없음")),
                            ln > 0 ? "" : "다른 사용자의 예매까지 반환될 수 있다.",
                            ln > 0 ? "" : "findByUserId(user.getId())처럼 로그인 사용자로 조회 범위를 제한한다.", ""));
                }
            } else if (t.contains("취소")) {
                ep = find(ctx, "POST", "/cancel", false);
                if (ep != null) {
                    findings.add(owner(ctx, p, ep, true,
                            "다른 사람의 예매번호로 취소 요청을 보내면 취소될 수 있다.",
                            "취소 전에 예매 소유자와 로그인 사용자를 비교한다."));
                }
            } else if (t.contains("결제") || t.contains("금액")) {
                ep = find(ctx, "POST", "/payments", false);
                if (ep != null) {
                    int bad = line(ctx, ep, CLIENT_PRICE);
                    int good = line(ctx, ep, SERVER_PRICE);
                    Map<String, Object> c = bad > 0
                            ? cond("결제 금액을 서버에 저장된 좌석 가격으로 정하는가", "absent", bad, "요청 바디의 가격을 그대로 결제 금액으로 넘김")
                            : good > 0
                            ? cond("결제 금액을 서버에 저장된 좌석 가격으로 정하는가", "present", good, "서버에 저장된 좌석 가격을 사용함")
                            : cond("결제 금액을 서버에 저장된 좌석 가격으로 정하는가", "uncertain", null, "금액을 정하는 줄을 찾지 못함");
                    findings.add(finding(p, ep, List.of(c),
                            bad > 0 ? "요청의 price 값을 바꾸면 정가와 다른 금액으로 결제될 수 있다." : good > 0 ? "" : "금액 계산 위치를 확인해야 한다.",
                            bad > 0 ? "seat.getPrice()를 사용하고, 요청 DTO에서 price 필드를 없앤다." : "",
                            good > 0 || bad > 0 ? "" : "금액을 계산하는 서비스 코드를 확인한다."));
                }
            } else if (t.contains("ADMIN") || t.contains("관리자") || t.contains("/admin")) {
                ep = find(ctx, null, "/admin/", false);
                if (ep != null) {
                    int ln = line(ctx, ep, ADMIN);
                    findings.add(finding(p, ep, List.of(
                                    cond("ADMIN 권한을 확인하는가", ln > 0 ? "present" : "absent", ln > 0 ? ln : null,
                                            ln > 0 ? "역할 검사 어노테이션이 있음" : "메서드와 어노테이션 어디에도 역할 확인이 없음")),
                            ln > 0 ? "" : "일반 사용자가 이 API를 직접 호출해도 실행될 수 있다.",
                            ln > 0 ? "" : "@PreAuthorize(\"hasRole('ADMIN')\")를 붙인다.",
                            ln > 0 ? "" : "SecurityConfig에서 /api/admin/**를 ADMIN으로 이미 제한하고 있다면 오탐일 수 있다."));
                }
            }
        }
        return new LlmResponse(Json.stringify(Map.of("findings", findings)), false);
    }

    private Map<String, Object> owner(FileContext ctx, Policy p, Endpoint ep, boolean allowDelegate, String bad, String fix) {
        int auth = line(ctx, ep, AUTH_PRINCIPAL);
        int cmp = line(ctx, ep, OWNER);
        int del = allowDelegate ? line(ctx, ep, DELEGATE) : -1;
        List<Object> conds = new ArrayList<>();
        conds.add(cond("로그인 사용자를 받는가", auth > 0 ? "present" : "absent", auth > 0 ? auth : null,
                auth > 0 ? "@AuthenticationPrincipal로 로그인 사용자를 받음" : "로그인 사용자를 받지 않음"));
        if (cmp > 0) {
            conds.add(cond("예매 소유자와 로그인 사용자를 비교하는가", "present", cmp, "소유자와 로그인 사용자를 비교함"));
            return finding(p, ep, conds, "", "", "");
        }
        if (del > 0) {
            conds.add(cond("예매 소유자와 로그인 사용자를 비교하는가", "uncertain", del, "서비스로 위임해서 이 코드만으로는 비교 여부를 알 수 없음"));
            return finding(p, ep, conds, "컨트롤러에는 소유자 비교가 없다. 서비스에서도 비교하지 않으면 다른 사람의 예매를 처리할 수 있다.",
                    "", "서비스 메서드 구현에서 예매 소유자와 로그인 사용자를 비교하는지 확인한다.");
        }
        conds.add(cond("예매 소유자와 로그인 사용자를 비교하는가", "absent", null, "조회한 뒤 소유자 비교 없이 처리함"));
        return finding(p, ep, conds, bad, fix, "");
    }

    private static Map<String, Object> finding(Policy p, Endpoint ep, List<Object> conds, String expl, String fix, String check) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("policy_id", p.id());
        f.put("endpoint", ep.key());
        f.put("conditions", conds);
        f.put("explanation", expl);
        f.put("fix", fix);
        f.put("check", check);
        return f;
    }

    private static Map<String, Object> cond(String name, String j, Integer line, String reason) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("condition", name);
        c.put("judgement", j);
        c.put("evidence_line", line);
        c.put("reason", reason);
        return c;
    }

    private static Endpoint find(FileContext ctx, String method, String pathPart, boolean endsWith) {
        for (Endpoint e : ctx.endpoints()) {
            if (method != null && !e.method().equals(method)) {
                continue;
            }
            String norm = e.path().replaceAll("\\{[^}]*}", "{}");
            if (endsWith ? norm.endsWith(pathPart) : norm.contains(pathPart)) {
                return e;
            }
        }
        return null;
    }

    private static int line(FileContext ctx, Endpoint ep, Pattern p) {
        for (int i = ep.start(); i <= ep.end() && i <= ctx.lines().size(); i++) {
            if (p.matcher(ctx.lines().get(i - 1)).find()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String label() {
        return "mock / 데모용 흉내 (실제 LLM 아님)";
    }
}
