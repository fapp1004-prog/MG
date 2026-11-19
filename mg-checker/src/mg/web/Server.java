package mg.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mg.Config;
import mg.check.CheckRunner;
import mg.llm.LlmClient;
import mg.scan.ProjectScanner;
import mg.util.Json;
import mg.util.Texts;

/**
 * 로컬 전용 HTTP 서버 (127.0.0.1에만 열린다).
 * 다른 웹사이트가 이 서버를 몰래 부르지 못하도록 Host·Origin 검사와 사용자 지정 헤더(X-MG)를 요구한다.
 */
public final class Server {

    private static final Pattern RUN_PATH = Pattern.compile("^/api/runs/([0-9A-Za-z-]{1,40})(/[a-z]+)?$");
    private static final int MAX_BODY = 2_000_000;

    private final Config cfg;
    private final LlmClient llm;
    private final CheckRunner runner;
    private final Path webDir;
    private final Set<String> allowedHosts;
    private final Map<String, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private int loginFailures;
    private long lockedUntil;
    private HttpServer http;

    public Server(Config cfg, LlmClient llm) {
        this.cfg = cfg;
        this.llm = llm;
        this.runner = new CheckRunner(llm, cfg.runsDir);
        this.webDir = cfg.baseDir.resolve("web");
        this.allowedHosts = Set.of("127.0.0.1:" + cfg.port, "localhost:" + cfg.port);
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", cfg.port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mg-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !allowedHosts.contains(host.toLowerCase())) {
                send(ex, 403, "text/plain; charset=utf-8", "허용되지 않은 Host입니다.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(ex, path);
            } else {
                staticFile(ex, path);
            }
        } catch (ApiError e) {
            json(ex, e.code, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            json(ex, 500, Map.of("error", "서버 오류: " + e));
        } finally {
            ex.close();
        }
    }

    // ───────── API ─────────

    private void api(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("POST")) {
            checkCsrf(ex);
        }
        if (method.equals("POST") && path.equals("/api/login")) {
            login(ex);
            return;
        }
        if (method.equals("POST") && path.equals("/api/logout")) {
            String token = sessionToken(ex);
            if (token != null) {
                sessions.remove(token);
            }
            ex.getResponseHeaders().add("Set-Cookie", "mg_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            json(ex, 200, Map.of("loggedIn", false));
            return;
        }
        if (method.equals("GET") && path.equals("/api/session")) {
            json(ex, 200, Map.of("loggedIn", validSession(ex), "user", validSession(ex) ? cfg.loginUser : ""));
            return;
        }
        if (!validSession(ex)) {
            throw new ApiError(401, "로그인이 필요합니다.");
        }
        if (method.equals("GET") && path.equals("/api/status")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", llm.label());
            m.put("provider", cfg.provider);
            m.put("keyConfigured", cfg.keyConfigured());
            m.put("user", cfg.loginUser);
            Path demo = cfg.baseDir.getParent() == null ? null : cfg.baseDir.getParent().resolve("demo-ticket");
            m.put("suggestedPath", demo != null && Files.isDirectory(demo) ? demo.toAbsolutePath().normalize().toString() : "");
            json(ex, 200, m);
            return;
        }
        if (method.equals("POST") && path.equals("/api/scan")) {
            Map<String, Object> body = body(ex);
            Path root = rootOf(body);
            try {
                json(ex, 200, ProjectScanner.scan(root).toMap());
            } catch (IOException e) {
                throw new ApiError(400, "폴더를 읽을 수 없습니다: " + e.getMessage());
            }
            return;
        }
        if (method.equals("POST") && path.equals("/api/policy")) {
            Map<String, Object> body = body(ex);
            Path root = rootOf(body);
            try {
                Path file = Texts.resolveInside(root, Json.str(body, "file"));
                json(ex, 200, Map.of("text", Texts.read(file)));
            } catch (IOException e) {
                throw new ApiError(400, e.getMessage());
            }
            return;
        }
        if (method.equals("POST") && path.equals("/api/check")) {
            if (!cfg.keyConfigured()) {
                throw new ApiError(400, "API 키가 설정되지 않았습니다. mg-checker 폴더의 mg.properties에 api.key를 넣고 다시 실행하세요. (키 없이 흐름만 보려면 provider=mock)");
            }
            Map<String, Object> body = body(ex);
            Path root = rootOf(body);
            List<String> files = new ArrayList<>();
            List<Object> fl = Json.asList(body.get("files"));
            if (fl != null) {
                fl.forEach(o -> files.add(String.valueOf(o)));
            }
            CheckRunner.Run run;
            try {
                run = runner.start(root, files, Json.str(body, "policyText"));
            } catch (IOException e) {
                throw new ApiError(400, e.getMessage());
            }
            json(ex, 200, Map.of("id", run.id));
            return;
        }
        if (method.equals("GET") && path.equals("/api/runs")) {
            json(ex, 200, Map.of("runs", runner.history()));
            return;
        }
        Matcher m = RUN_PATH.matcher(path);
        if (m.matches()) {
            String id = m.group(1);
            String sub = m.group(2) == null ? "" : m.group(2);
            CheckRunner.Run run = runner.load(id);
            if (run == null) {
                throw new ApiError(404, "실행 기록이 없습니다: " + id);
            }
            if (method.equals("GET") && sub.isEmpty()) {
                json(ex, 200, run.snapshot());
                return;
            }
            if (method.equals("GET") && sub.equals("/report")) {
                Path rep = run.dir.resolve("report.md");
                byte[] b = Files.isRegularFile(rep) ? Files.readAllBytes(rep) : new byte[0];
                send(ex, 200, "text/markdown; charset=utf-8", b);
                return;
            }
            if (method.equals("GET") && sub.equals("/artifacts")) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("raw", readDir(run.dir.resolve("raw")));
                out.put("prompts", readDir(run.dir.resolve("prompts")));
                out.put("dir", run.dir.toAbsolutePath().toString());
                json(ex, 200, out);
                return;
            }
            if (method.equals("POST") && sub.equals("/verdict")) {
                Map<String, Object> body = body(ex);
                String key = Json.str(body, "key");
                String value = Json.str(body, "value");
                if (key == null || key.length() > 500) {
                    throw new ApiError(400, "잘못된 key");
                }
                if (value != null && !value.equals("ok") && !value.equals("fp") && !value.isEmpty()) {
                    throw new ApiError(400, "value는 ok, fp, 빈 값 중 하나");
                }
                runner.saveVerdict(run, key, value);
                json(ex, 200, Map.of("saved", true));
                return;
            }
        }
        throw new ApiError(404, "없는 API: " + method + " " + path);
    }

    // ───────── 로그인 ─────────

    private synchronized void login(HttpExchange ex) throws IOException, ApiError {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            throw new ApiError(429, "로그인 실패가 많아 잠시 잠겼습니다. " + ((lockedUntil - now) / 1000 + 1) + "초 뒤 다시 시도하세요.");
        }
        Map<String, Object> body = body(ex);
        String user = Json.str(body, "user");
        String pw = Json.str(body, "password");
        boolean ok = user != null && pw != null
                && java.security.MessageDigest.isEqual(user.getBytes(StandardCharsets.UTF_8), cfg.loginUser.getBytes(StandardCharsets.UTF_8))
                & java.security.MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), cfg.loginPassword.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            loginFailures++;
            if (loginFailures >= 5) {
                lockedUntil = now + 30_000;
                loginFailures = 0;
            }
            throw new ApiError(401, "아이디 또는 비밀번호가 맞지 않습니다.");
        }
        loginFailures = 0;
        byte[] b = new byte[32];
        random.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) {
            token.append(String.format("%02x", x));
        }
        long ttl = cfg.sessionHours * 3_600_000L;
        sessions.entrySet().removeIf(e -> e.getValue() < now);
        sessions.put(token.toString(), now + ttl);
        ex.getResponseHeaders().add("Set-Cookie", "mg_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (ttl / 1000));
        json(ex, 200, Map.of("loggedIn", true, "user", cfg.loginUser));
    }

    private String sessionToken(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String header : cookies) {
            // 브라우저는 "mg_session=값", 일부 클라이언트는 "$Version=1, mg_session=\"값\"" 형태로 보낸다
            for (String part : header.split("[;,]")) {
                String t = part.trim();
                if (t.startsWith("mg_session=")) {
                    String v = t.substring("mg_session=".length()).replace("\"", "");
                    return v.matches("[0-9a-f]{64}") ? v : null;
                }
            }
        }
        return null;
    }

    private boolean validSession(HttpExchange ex) {
        String token = sessionToken(ex);
        if (token == null) {
            return false;
        }
        Long exp = sessions.get(token);
        if (exp == null || exp < System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    private Path rootOf(Map<String, Object> body) throws ApiError {
        try {
            return ProjectScanner.checkRoot(Json.str(body, "path"));
        } catch (IOException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    private void checkCsrf(HttpExchange ex) throws ApiError {
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-MG"))) {
            throw new ApiError(403, "X-MG 헤더가 없는 요청은 거부합니다.");
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("null")) {
            try {
                URI u = URI.create(origin);
                String hp = (u.getHost() + ":" + u.getPort()).toLowerCase();
                if (!allowedHosts.contains(hp)) {
                    throw new ApiError(403, "다른 사이트에서 온 요청은 거부합니다.");
                }
            } catch (IllegalArgumentException e) {
                throw new ApiError(403, "잘못된 Origin");
            }
        }
    }

    private List<Map<String, Object>> readDir(Path dir) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getFileName().toString());
                m.put("text", Files.readString(p, StandardCharsets.UTF_8));
                out.add(m);
            }
        }
        return out;
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException, ApiError {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY) {
                    throw new ApiError(413, "요청이 너무 큽니다.");
                }
            }
            String s = bos.toString(StandardCharsets.UTF_8);
            if (s.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> m = Json.asMap(Json.parse(s));
            if (m == null) {
                throw new ApiError(400, "JSON 객체가 필요합니다.");
            }
            return m;
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "JSON 형식 오류: " + e.getMessage());
        }
    }

    // ───────── 정적 파일 ─────────

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, "text/plain; charset=utf-8", "GET만 가능".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = path.equals("/") ? "index.html" : path.substring(1);
        // web/ 바로 아래 파일과 web/fonts/ 아래 글꼴만 제공 (다른 하위 폴더, 상위 폴더는 거부)
        if (!name.matches("(fonts/)?[A-Za-z0-9._-]{1,64}") || name.contains("..")) {
            send(ex, 404, "text/plain; charset=utf-8", "없음".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path f = webDir.resolve(name);
        if (!Files.isRegularFile(f)) {
            send(ex, 404, "text/plain; charset=utf-8", "없음".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(f));
    }

    // ───────── 응답 ─────────

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static final class ApiError extends Exception {
        final int code;

        ApiError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
