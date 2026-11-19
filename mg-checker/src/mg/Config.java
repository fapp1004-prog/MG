package mg;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 설정. mg.properties 파일을 읽고, 같은 이름의 환경변수가 있으면 그 값을 우선한다.
 * API 키는 화면이나 로그로 절대 내보내지 않는다.
 */
public final class Config {

    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-5-mini";

    public final Path baseDir;
    public final String provider;      // anthropic | openai | mock
    public final String apiKey;
    public final String model;
    public final int port;
    public final int maxTokens;
    public final Double temperature;   // null이면 요청에 넣지 않음
    public final String anthropicBaseUrl;
    public final String openaiBaseUrl;
    public final int timeoutSeconds;
    public final Path runsDir;
    public final String loginUser;
    public final String loginPassword;
    public final boolean passwordGenerated;
    public final int sessionHours;

    private Config(Path baseDir, Properties p) {
        this.baseDir = baseDir;
        String key = pick(p, "api.key", "MG_API_KEY", "");
        String prov = pick(p, "provider", "MG_PROVIDER", "").toLowerCase(Locale.ROOT);
        if (prov.isBlank()) {
            // 키 모양으로 공급자를 짐작한다. sk-ant- 는 Anthropic, 그 밖의 sk- 는 OpenAI.
            if (key.startsWith("sk-ant-")) {
                prov = "anthropic";
            } else if (key.startsWith("sk-")) {
                prov = "openai";
            } else {
                prov = "anthropic";
            }
        }
        if (!prov.equals("anthropic") && !prov.equals("openai") && !prov.equals("mock")) {
            throw new IllegalArgumentException("provider는 anthropic, openai, mock 중 하나여야 합니다: " + prov);
        }
        this.provider = prov;
        this.apiKey = key.trim();
        String m = pick(p, "model", "MG_MODEL", "");
        if (m.isBlank()) {
            m = prov.equals("openai") ? DEFAULT_OPENAI_MODEL : prov.equals("mock") ? "mock" : DEFAULT_ANTHROPIC_MODEL;
        }
        this.model = m;
        this.port = Integer.parseInt(pick(p, "port", "MG_PORT", "8090"));
        this.maxTokens = Integer.parseInt(pick(p, "max.tokens", "MG_MAX_TOKENS", prov.equals("openai") ? "16000" : "8192"));
        String t = pick(p, "temperature", "MG_TEMPERATURE", prov.equals("anthropic") ? "0" : "");
        this.temperature = t.isBlank() ? null : Double.valueOf(t);
        this.anthropicBaseUrl = stripSlash(pick(p, "anthropic.base.url", "MG_ANTHROPIC_BASE_URL", "https://api.anthropic.com"));
        this.openaiBaseUrl = stripSlash(pick(p, "openai.base.url", "MG_OPENAI_BASE_URL", "https://api.openai.com"));
        this.timeoutSeconds = Integer.parseInt(pick(p, "timeout.seconds", "MG_TIMEOUT_SECONDS", "180"));
        this.runsDir = baseDir.resolve("runs");
        this.loginUser = pick(p, "login.user", "MG_LOGIN_USER", "admin");
        String pw = pick(p, "login.password", "MG_LOGIN_PASSWORD", "");
        if (pw.isBlank()) {
            // 비밀번호를 정하지 않았으면 실행할 때마다 새로 만든다 (기본 비밀번호를 두지 않기 위해)
            this.loginPassword = randomPassword();
            this.passwordGenerated = true;
        } else {
            this.loginPassword = pw;
            this.passwordGenerated = false;
        }
        this.sessionHours = Integer.parseInt(pick(p, "session.hours", "MG_SESSION_HOURS", "8"));
    }

    private static String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static Config load(Path baseDir) throws IOException {
        Properties p = new Properties();
        Path file = baseDir.resolve("mg.properties");
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return new Config(baseDir, p);
    }

    /** 테스트용: 파일 없이 값을 직접 넣는다. */
    public static Config of(Path baseDir, Properties p) {
        return new Config(baseDir, p);
    }

    public boolean keyConfigured() {
        return provider.equals("mock") || !apiKey.isBlank();
    }

    private static String pick(Properties p, String prop, String env, String def) {
        String e = System.getenv(env);
        if (e != null && !e.isBlank()) {
            return e.trim();
        }
        String v = p.getProperty(prop);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return def;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
