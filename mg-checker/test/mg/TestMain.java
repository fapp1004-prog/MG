package mg;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import mg.check.CheckRunner;
import mg.check.FileContext;
import mg.check.FormatException;
import mg.check.PromptBuilder;
import mg.check.ResultValidator;
import mg.llm.LlmClient;
import mg.llm.LlmException;
import mg.llm.LlmResponse;
import mg.llm.MockClient;
import mg.scan.Endpoint;
import mg.scan.EndpointParser;
import mg.scan.PolicyParser;
import mg.scan.ProjectScanner;
import mg.util.Json;
import mg.util.Texts;
import mg.web.Server;

/**
 * 외부 테스트 라이브러리 없이 돌리는 테스트. 실행: test.bat 또는 test.sh
 * demo-ticket 프로젝트(../demo-ticket)를 실제 입력으로 쓴다.
 */
public final class TestMain {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    private static void check(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed.add(name);
            System.out.println("  실패: " + name);
        }
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            name = name + " (기대: " + expected + ", 실제: " + actual + ")";
        }
        check(ok, name);
    }

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getProperty("mg.home", ".")).toAbsolutePath().normalize();
        Path demo = base.getParent().resolve("demo-ticket");

        json();
        parser(demo);
        parserTricky();
        policies(demo);
        scanner(demo);
        validator(demo);
        runnerMock(demo);
        runnerRetryAndFatal(demo);
        http(base, demo);

        System.out.println();
        System.out.println("통과 " + passed + "개, 실패 " + failed.size() + "개");
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ───────── JSON ─────────
    static void json() {
        System.out.println("[JSON]");
        Object o = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\",true,null],\"b\":{\"c\":-3}}");
        Map<String, Object> m = Json.asMap(o);
        eq(5, Json.asList(m.get("a")).size(), "배열 길이");
        eq(1L, Json.asList(m.get("a")).get(0), "정수는 Long");
        eq(2.5, Json.asList(m.get("a")).get(1), "실수는 Double");
        eq("x\né", Json.asList(m.get("a")).get(2), "이스케이프");
        eq(-3L, Json.asMap(m.get("b")).get("c"), "음수");
        String s = Json.stringify(o);
        eq(Json.stringify(Json.parse(s)), s, "왕복 동일");
        eq(21, Json.intOrNull("21"), "문자열 숫자 → 정수");
        eq(null, Json.intOrNull("21행"), "숫자 아닌 문자열 → null");
        boolean threw = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "잘못된 JSON은 예외");
        eq("\"\\u0001\"", Json.stringify("\u0001"), "제어문자 이스케이프");
    }

    // ───────── 엔드포인트 파서 ─────────
    static void parser(Path demo) throws Exception {
        System.out.println("[엔드포인트 파서 - 데모 코드]");
        String vuln = Texts.read(demo.resolve("src/main/java/com/mg/demo/controller/vuln/BookingController.java"));
        List<Endpoint> eps = EndpointParser.parse(vuln);
        List<String> keys = eps.stream().map(Endpoint::key).toList();
        eq(List.of("GET /api/concerts", "GET /api/bookings/{id}", "GET /api/bookings", "POST /api/bookings/{id}/cancel",
                "POST /api/payments", "POST /api/admin/seats/assign"), keys, "취약 버전 API 6개와 클래스 경로 합치기");
        List<String> lines = Arrays.asList(vuln.split("\n", -1));
        Endpoint getBooking = eps.get(1);
        check(lines.get(getBooking.start() - 1).contains("@GetMapping(\"/bookings/{id}\")"), "범위 시작 = 어노테이션 줄");
        eq("    }", lines.get(getBooking.end() - 1), "범위 끝 = 메서드 닫는 중괄호");
        eq("getBooking", getBooking.methodName(), "메서드 이름");

        String fixed = Texts.read(demo.resolve("src/main/java/com/mg/demo/controller/fixed/BookingController.java"));
        List<Endpoint> feps = EndpointParser.parse(fixed);
        eq(6, feps.size(), "고친 버전 API 6개");
        Endpoint assign = feps.get(5);
        List<String> flines = Arrays.asList(fixed.split("\n", -1));
        check(flines.get(assign.start() - 1).contains("@PreAuthorize"), "매핑 위의 @PreAuthorize도 범위에 포함");

        String acct = Texts.read(demo.resolve("src/main/java/com/mg/demo/controller/AccountController.java"));
        eq(List.of("GET /api/me", "GET /api/seats"), EndpointParser.parse(acct).stream().map(Endpoint::key).toList(), "AccountController API");
    }

    static void parserTricky() {
        System.out.println("[엔드포인트 파서 - 까다로운 경우]");
        String src = """
                @RestController
                @RequestMapping(value = "/v1/")
                public class X {
                    // @GetMapping("/commented") 주석 안의 매핑은 무시
                    /* @PostMapping("/block") */
                    @GetMapping(
                        value = "/multi",
                        produces = "application/json")
                    public String multi() {
                        String s = "{ not a brace }";
                        char c = '{';
                        return s;
                    }

                    @RequestMapping(path = {"/a", "/b"}, method = {RequestMethod.GET, RequestMethod.POST})
                    public void both() { if (true) { int x = 1; } }

                    @DeleteMapping
                    public void root() {}

                    @PutMapping(PATH_CONST)
                    public void constant() {}

                    @PatchMapping("/text")
                    public String text() {
                        return \"\"\"
                            } } {
                            \"\"\";
                    }

                    @GetMapping("/after")
                    public void after() {}
                }
                """;
        List<Endpoint> eps = EndpointParser.parse(src);
        List<String> keys = eps.stream().map(Endpoint::key).toList();
        eq(List.of("GET /v1/multi", "GET /v1/a", "GET /v1/b", "POST /v1/a", "POST /v1/b", "DELETE /v1",
                "PUT /v1/[PATH_CONST]", "PATCH /v1/text", "GET /v1/after"), keys, "여러 줄·배열·method·상수·텍스트 블록");
        Endpoint multi = eps.get(0);
        eq(6, multi.start(), "여러 줄 어노테이션 시작 줄");
        eq(13, multi.end(), "문자열 속 중괄호 무시한 끝 줄");
        Endpoint text = eps.get(7);
        String[] lines = src.split("\n");
        eq("    }", lines[text.end() - 1], "텍스트 블록 속 중괄호 무시");
        eq("GET /api/x/{}", Endpoint.normalizeKey("get  /api/x/{bookingId}/"), "키 정규화");
    }

    static void policies(Path demo) throws Exception {
        System.out.println("[정책 파서]");
        var ps = PolicyParser.parse(Texts.read(demo.resolve("docs/권한정책.md")));
        eq(5, ps.size(), "정책 5개 (제목 줄 제외)");
        eq("P4", ps.get(3).id(), "번호 유지");
        check(ps.get(4).text().contains("ADMIN"), "본문 유지");
        var p2 = PolicyParser.parse("본인만 조회\n- 관리자만 삭제\n3) 가격은 서버 값");
        eq(List.of("P1", "P2", "P3"), p2.stream().map(PolicyParser.Policy::id).toList(), "번호 없는 줄은 순서대로");
    }

    static void scanner(Path demo) throws Exception {
        System.out.println("[프로젝트 스캐너]");
        var r = ProjectScanner.scan(ProjectScanner.checkRoot(demo.toString()));
        List<String> paths = r.controllers().stream().map(ProjectScanner.ControllerFile::path).toList();
        eq(List.of("src/main/java/com/mg/demo/controller/AccountController.java",
                "src/main/java/com/mg/demo/controller/fixed/BookingController.java",
                "src/main/java/com/mg/demo/controller/vuln/BookingController.java"), paths, "컨트롤러 3개만 (Advice·서비스 제외)");
        eq(List.of("docs/권한정책.md"), r.policyFiles(), "정책 문서 찾기");
        boolean threw = false;
        try {
            Texts.resolveInside(demo, "../mg-checker/src/mg/Main.java");
        } catch (java.io.IOException e) {
            threw = true;
        }
        check(threw, "프로젝트 밖 경로 거부");
        threw = false;
        try {
            ProjectScanner.checkRoot("Z:/없는/폴더");
        } catch (java.io.IOException e) {
            threw = true;
        }
        check(threw, "없는 폴더 거부");
        check(ProjectScanner.checkRoot("\"" + demo + "\"") != null, "따옴표로 감싼 경로 허용 (탐색기에서 경로 복사)");
    }

    static FileContext ctx(Path demo, String rel) throws Exception {
        String src = Texts.read(demo.resolve(rel));
        return new FileContext(rel, Arrays.asList(src.split("\n", -1)), EndpointParser.parse(src),
                PolicyParser.parse(Texts.read(demo.resolve("docs/권한정책.md"))));
    }

    static void validator(Path demo) throws Exception {
        System.out.println("[결과 검증 규칙]");
        FileContext c = ctx(demo, "src/main/java/com/mg/demo/controller/vuln/BookingController.java");
        String prompt = PromptBuilder.build(c);
        check(prompt.contains("GET /api/bookings/{id}") && prompt.contains("P5.") && prompt.contains("검사 대상 시작"), "지시문에 API 목록·정책·코드 경계 포함");

        String raw = "```json\n" + """
                {"findings":[
                 {"policy_id":"P1","endpoint":"GET /api/bookings/{bookingId}","conditions":[
                   {"condition":"로그인 사용자를 받는가","judgement":"present","evidence_line":null,"reason":"근거 없이 있음"},
                   {"condition":"소유자 비교","judgement":"absent","evidence_line":"67","reason":"비교 없음"}],
                  "explanation":"남의 예매가 보일 수 있음","fix":"소유자 비교","check":""},
                 {"policy_id":"P9","endpoint":"GET /api/bookings","conditions":[]},
                 {"policy_id":"P2","endpoint":"GET /api/secret","conditions":[]},
                 {"policy_id":"2","endpoint":"GET /api/bookings","conditions":[{"condition":"본인 기준 조회","judgement":"있음","evidence_line":74,"reason":"ok"}]},
                 {"policy_id":"P3","endpoint":null,"conditions":[]}
                ]}
                """ + "\n```";
        var out = ResultValidator.validate(raw, c);
        eq(2, out.cards().size(), "없는 정책·없는 API·null API 항목은 버림");
        eq(1, out.downgrades(), "근거 줄 없는 '있음' 강등 1건");
        Map<String, Object> p1 = out.cards().get(0);
        eq("missing", p1.get("status"), "없음이 하나라도 있으면 누락 의심");
        eq("GET /api/bookings/{id}", p1.get("endpoint"), "경로 변수 이름이 달라도 코드의 키로 맞춤");
        Map<String, Object> cond0 = Json.asMap(Json.asList(p1.get("conditions")).get(0));
        eq("uncertain", cond0.get("final"), "강등된 최종 판단");
        eq("present", cond0.get("llm"), "LLM 원래 판단 보존");
        eq(67, Json.asMap(Json.asList(p1.get("conditions")).get(1)).get("line"), "문자열 줄번호도 읽음");
        eq("P2", out.cards().get(1).get("policyId"), "숫자만 있는 정책 번호 → P2");
        eq("ok", out.cards().get(1).get("status"), "한국어 판단(있음)도 읽음");
        eq(2, out.notes().size(), "버린 이유 메모 2건 (API가 null인 항목은 메모 없이 건너뜀)");

        boolean threw = false;
        try {
            ResultValidator.validate("죄송하지만 판단할 수 없습니다.", c);
        } catch (FormatException e) {
            threw = true;
        }
        check(threw, "JSON 아닌 응답은 FormatException");
        threw = false;
        try {
            ResultValidator.validate("{\"result\":[]}", c);
        } catch (FormatException e) {
            threw = true;
        }
        check(threw, "findings 없으면 FormatException");
    }

    static Map<String, Object> runAndWait(CheckRunner runner, Path demo, List<String> files) throws Exception {
        String policy = Texts.read(demo.resolve("docs/권한정책.md"));
        CheckRunner.Run run = runner.start(demo, files, policy);
        for (int i = 0; i < 200 && !(run.status.equals("done") || run.status.equals("error")); i++) {
            Thread.sleep(25);
        }
        return run.snapshot();
    }

    static void runnerMock(Path demo) throws Exception {
        System.out.println("[점검 실행 - mock]");
        Path runs = Files.createTempDirectory("mg-runs");
        CheckRunner runner = new CheckRunner(new MockClient(), runs);
        Map<String, Object> s = runAndWait(runner, demo, List.of(
                "src/main/java/com/mg/demo/controller/AccountController.java",
                "src/main/java/com/mg/demo/controller/vuln/BookingController.java"));
        eq("done", s.get("status"), "실행 완료");
        Map<String, Object> r = Json.asMap(s.get("result"));
        Map<String, Object> cnt = Json.asMap(r.get("counts"));
        eq(3L, cnt.get("missing"), "취약 버전: 누락 의심 3 (P1, P4, P5)");
        eq(1L, cnt.get("unsure"), "취약 버전: 불확실 1 (P3, 서비스 위임)");
        eq(1L, cnt.get("ok"), "취약 버전: 검사 있음 1 (P2)");
        eq(3L, cnt.get("nomatch"), "짝 없는 API 3 (concerts, me, seats)");
        eq(0L, cnt.get("nopair"), "API 없는 정책 0");
        eq(8, ((Number) r.get("endpointCount")).intValue(), "API 총 8개");
        String id = String.valueOf(s.get("id"));
        Path dir = runs.resolve(id);
        check(Files.isRegularFile(dir.resolve("result.json")), "result.json 저장");
        check(Files.isRegularFile(dir.resolve("report.md")), "report.md 저장");
        try (var st = Files.list(dir.resolve("raw"))) {
            eq(2L, st.count(), "파일마다 응답 원문 저장 (raw 2개)");
        } catch (Throwable t) {
            check(false, "raw 폴더 읽기");
        }

        // 개발자 확인 저장과 다시 불러오기
        CheckRunner.Run run = runner.get(id);
        runner.saveVerdict(run, "P1|src/main/java/com/mg/demo/controller/vuln/BookingController.java|GET /api/bookings/{id}", "ok");
        CheckRunner fresh = new CheckRunner(new MockClient(), runs);
        CheckRunner.Run loaded = fresh.load(id);
        check(loaded != null && "ok".equals(loaded.verdicts.values().iterator().next()), "개발자 확인이 디스크에 남고 다시 불러와짐");
        check(Files.readString(dir.resolve("report.md")).contains("맞음 1"), "보고서에 개발자 확인 반영");
        eq(1, fresh.history().size(), "실행 기록 목록");

        Map<String, Object> s2 = runAndWait(runner, demo, List.of("src/main/java/com/mg/demo/controller/fixed/BookingController.java"));
        Map<String, Object> cnt2 = Json.asMap(Json.asMap(s2.get("result")).get("counts"));
        eq(0L, cnt2.get("missing"), "고친 버전: 누락 의심 0");
        eq(0L, cnt2.get("unsure"), "고친 버전: 불확실 0");
        eq(5L, cnt2.get("ok"), "고친 버전: 검사 있음 5");
    }

    static void runnerRetryAndFatal(Path demo) throws Exception {
        System.out.println("[점검 실행 - 형식 재요청, 치명적 오류]");
        AtomicInteger calls = new AtomicInteger();
        MockClient mock = new MockClient();
        LlmClient flaky = new LlmClient() {
            @Override
            public LlmResponse complete(String prompt, FileContext ctx) throws LlmException {
                if (calls.incrementAndGet() == 1) {
                    return new LlmResponse("네, 점검해 보겠습니다! 결과는 다음과 같습니다...", false);
                }
                check(prompt.contains("[주의] 이전 응답을 읽을 수 없었다"), "재요청 지시문에 주의 문구");
                return mock.complete(prompt, ctx);
            }

            @Override
            public String label() {
                return "flaky";
            }
        };
        Path runs = Files.createTempDirectory("mg-runs");
        Map<String, Object> s = runAndWait(new CheckRunner(flaky, runs), demo,
                List.of("src/main/java/com/mg/demo/controller/vuln/BookingController.java"));
        Map<String, Object> r = Json.asMap(s.get("result"));
        eq(1, ((Number) r.get("formatRetries")).intValue(), "형식 오류 시 한 번 재요청");
        eq(3L, Json.asMap(r.get("counts")).get("missing"), "재요청 후 정상 결과");

        LlmClient badKey = new LlmClient() {
            @Override
            public LlmResponse complete(String prompt, FileContext ctx) throws LlmException {
                throw new LlmException("Anthropic API 오류 (401): invalid x-api-key", true);
            }

            @Override
            public String label() {
                return "bad";
            }
        };
        Map<String, Object> s2 = runAndWait(new CheckRunner(badKey, runs), demo, List.of(
                "src/main/java/com/mg/demo/controller/vuln/BookingController.java",
                "src/main/java/com/mg/demo/controller/fixed/BookingController.java"));
        eq("error", s2.get("status"), "키 오류는 실행 실패");
        List<Object> files = Json.asList(s2.get("files"));
        eq("skipped", Json.asMap(files.get(1)).get("state"), "치명적 오류 뒤 파일은 건너뜀");
        check(String.valueOf(s2.get("error")).contains("401"), "오류 메시지 전달");
    }

    // ───────── HTTP ─────────
    static void http(Path base, Path demo) throws Exception {
        System.out.println("[로컬 서버]");
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        Path tmpBase = Files.createTempDirectory("mg-base");
        Files.createDirectories(tmpBase.resolve("web"));
        Files.copy(base.resolve("web/index.html"), tmpBase.resolve("web/index.html"));
        Files.copy(base.resolve("web/app.js"), tmpBase.resolve("web/app.js"));
        Files.createDirectories(tmpBase.resolve("web/fonts"));
        Files.copy(base.resolve("web/fonts/galmuri11.woff2"), tmpBase.resolve("web/fonts/galmuri11.woff2"));
        Properties p = new Properties();
        p.setProperty("provider", "mock");
        p.setProperty("port", String.valueOf(port));
        p.setProperty("login.user", "admin");
        p.setProperty("login.password", "test-pass-1234");
        Config cfg = Config.of(tmpBase, p);
        Server server = new Server(cfg, new MockClient());
        server.start();
        try {
            HttpClient anon = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            HttpClient hc = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                    .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build();
            String url = "http://127.0.0.1:" + port;

            // 로그인 전: 화면은 열리고, API는 막힌다
            HttpResponse<String> idx0 = anon.send(HttpRequest.newBuilder(URI.create(url + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, idx0.statusCode(), "로그인 전에도 화면 파일은 제공");
            HttpResponse<String> ses0 = anon.send(HttpRequest.newBuilder(URI.create(url + "/api/session")).build(), HttpResponse.BodyHandlers.ofString());
            check(ses0.statusCode() == 200 && ses0.body().contains("\"loggedIn\":false"), "세션 없음 확인");
            HttpResponse<String> st0 = anon.send(HttpRequest.newBuilder(URI.create(url + "/api/status")).build(), HttpResponse.BodyHandlers.ofString());
            eq(401, st0.statusCode(), "세션 없는 API 거부");
            HttpResponse<String> scan0 = post(anon, url + "/api/scan", Json.stringify(Map.of("path", demo.toString())));
            eq(401, scan0.statusCode(), "세션 없는 스캔 거부");
            HttpResponse<String> wrong = post(hc, url + "/api/login", Json.stringify(Map.of("user", "admin", "password", "nope")));
            eq(401, wrong.statusCode(), "틀린 비밀번호 거부");
            HttpResponse<String> forged = anon.send(HttpRequest.newBuilder(URI.create(url + "/api/status"))
                    .header("Cookie", "mg_session=" + "0".repeat(64)).build(), HttpResponse.BodyHandlers.ofString());
            eq(401, forged.statusCode(), "지어낸 세션 쿠키 거부");
            HttpResponse<String> noHdrLogin = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/login"))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Map.of("user", "admin", "password", "test-pass-1234")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            eq(403, noHdrLogin.statusCode(), "X-MG 헤더 없는 로그인 거부");
            HttpResponse<String> login = post(hc, url + "/api/login", Json.stringify(Map.of("user", "admin", "password", "test-pass-1234")));
            eq(200, login.statusCode(), "로그인");
            String cookie = login.headers().firstValue("set-cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "세션 쿠키 HttpOnly·SameSite");
            HttpResponse<String> ses1 = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/session")).build(), HttpResponse.BodyHandlers.ofString());
            check(ses1.body().contains("\"loggedIn\":true"), "로그인 뒤 세션 유지");
            HttpResponse<String> st = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/status")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, st.statusCode(), "GET /api/status");
            check(st.body().contains("\"keyConfigured\":true"), "mock은 키 불필요");

            HttpResponse<String> idx = hc.send(HttpRequest.newBuilder(URI.create(url + "/")).build(), HttpResponse.BodyHandlers.ofString());
            check(idx.statusCode() == 200 && idx.body().contains("Missing Guard"), "화면 제공");

            String scanBody = Json.stringify(Map.of("path", demo.toString()));
            HttpResponse<String> noHeader = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/scan"))
                    .POST(HttpRequest.BodyPublishers.ofString(scanBody)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, noHeader.statusCode(), "X-MG 헤더 없는 POST 거부");

            HttpResponse<String> evil = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/scan"))
                    .header("X-MG", "1").header("Origin", "https://evil.example")
                    .POST(HttpRequest.BodyPublishers.ofString(scanBody)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, evil.statusCode(), "다른 Origin 거부");

            HttpResponse<String> scan = post(hc, url + "/api/scan", scanBody);
            eq(200, scan.statusCode(), "POST /api/scan");
            check(scan.body().contains("vuln/BookingController.java"), "스캔 결과");

            HttpResponse<String> pol = post(hc, url + "/api/policy", Json.stringify(Map.of("path", demo.toString(), "file", "docs/권한정책.md")));
            check(pol.statusCode() == 200 && pol.body().contains("P5."), "정책 문서 읽기 (한글 파일명)");

            HttpResponse<String> esc = post(hc, url + "/api/policy", Json.stringify(Map.of("path", demo.toString(), "file", "../mg-checker/mg.properties.example")));
            eq(400, esc.statusCode(), "프로젝트 밖 파일 읽기 거부");

            String checkBody = Json.stringify(Map.of("path", demo.toString(),
                    "files", List.of("src/main/java/com/mg/demo/controller/vuln/BookingController.java"),
                    "policyText", Texts.read(demo.resolve("docs/권한정책.md"))));
            HttpResponse<String> chk = post(hc, url + "/api/check", checkBody);
            eq(200, chk.statusCode(), "POST /api/check");
            String id = Json.str(Json.asMap(Json.parse(chk.body())), "id");
            Map<String, Object> snap = null;
            for (int i = 0; i < 200; i++) {
                HttpResponse<String> g = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/runs/" + id)).build(), HttpResponse.BodyHandlers.ofString());
                snap = Json.asMap(Json.parse(g.body()));
                if ("done".equals(snap.get("status"))) {
                    break;
                }
                Thread.sleep(25);
            }
            eq("done", snap.get("status"), "실행 상태 조회");
            HttpResponse<String> v = post(hc, url + "/api/runs/" + id + "/verdict", Json.stringify(Map.of("key", "a|b|c", "value", "fp")));
            eq(200, v.statusCode(), "개발자 확인 저장");
            HttpResponse<String> rep = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/runs/" + id + "/report")).build(), HttpResponse.BodyHandlers.ofString());
            check(rep.statusCode() == 200 && rep.body().contains("# MG 점검 보고서"), "보고서 조회");
            HttpResponse<String> art = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/runs/" + id + "/artifacts")).build(), HttpResponse.BodyHandlers.ofString());
            check(art.statusCode() == 200 && art.body().contains("findings"), "원문 조회");
            HttpResponse<String> bad = post(hc, url + "/api/check", Json.stringify(Map.of("path", demo.toString(), "files", List.of(), "policyText", "P1. x")));
            eq(400, bad.statusCode(), "파일 없이 점검 요청 거부");

            HttpResponse<byte[]> font = hc.send(HttpRequest.newBuilder(URI.create(url + "/fonts/galmuri11.woff2")).build(), HttpResponse.BodyHandlers.ofByteArray());
            check(font.statusCode() == 200 && font.headers().firstValue("content-type").orElse("").startsWith("font/woff2"), "글꼴 파일 제공");
            HttpResponse<String> deep = hc.send(HttpRequest.newBuilder(URI.create(url + "/fonts/x/y.woff2")).build(), HttpResponse.BodyHandlers.ofString());
            eq(404, deep.statusCode(), "fonts 아래 더 깊은 경로 거부");
            HttpResponse<String> other = hc.send(HttpRequest.newBuilder(URI.create(url + "/src/mg/Main.java")).build(), HttpResponse.BodyHandlers.ofString());
            eq(404, other.statusCode(), "web 밖 파일 거부");
            HttpResponse<String> out = post(hc, url + "/api/logout", "{}");
            eq(200, out.statusCode(), "로그아웃");
            HttpResponse<String> after = hc.send(HttpRequest.newBuilder(URI.create(url + "/api/runs")).build(), HttpResponse.BodyHandlers.ofString());
            eq(401, after.statusCode(), "로그아웃 뒤 API 거부");
            int last = 0;
            for (int i = 0; i < 6; i++) {
                last = post(anon, url + "/api/login", Json.stringify(Map.of("user", "admin", "password", "x" + i))).statusCode();
            }
            eq(429, last, "연속 실패 뒤 잠금");
            eq(429, post(anon, url + "/api/login", Json.stringify(Map.of("user", "admin", "password", "test-pass-1234"))).statusCode(), "잠금 중에는 맞는 비밀번호도 거부");

            // Host 헤더 검사 (DNS 리바인딩 방지): 원시 소켓으로 다른 Host를 보낸다
            try (Socket so = new Socket("127.0.0.1", port)) {
                OutputStream os = so.getOutputStream();
                os.write(("GET /api/status HTTP/1.1\r\nHost: attacker.example:" + port + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                os.flush();
                String first = new BufferedReader(new InputStreamReader(so.getInputStream(), StandardCharsets.UTF_8)).readLine();
                check(first != null && first.contains("403"), "다른 Host 거부");
            }
        } finally {
            server.stop();
        }
    }

    static HttpResponse<String> post(HttpClient hc, String url, String body) throws Exception {
        return hc.send(HttpRequest.newBuilder(URI.create(url)).header("X-MG", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
