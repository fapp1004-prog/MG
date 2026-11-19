package mg.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [규칙] Spring 컨트롤러 소스에서 API 목록과 메서드 범위를 뽑는다. LLM을 쓰지 않으므로 같은 입력에는 항상 같은 결과가 나온다.
 *
 * <p>처리하는 것: 클래스 위 @RequestMapping 경로 합치기, @Get/Post/Put/Delete/PatchMapping, 메서드 위 @RequestMapping(method=...),
 * 여러 줄에 걸친 어노테이션, 경로 배열({"/a","/b"}), 주석과 문자열 안의 중괄호 무시.
 * 처리하지 않는 것: 상수로 된 경로의 실제 값(대괄호로 표시), 다른 프레임워크(FastAPI, Express 등).
 */
public final class EndpointParser {

    private static final Pattern ANNOTATION = Pattern.compile("(?<![\\w.$])@([A-Za-z_][\\w.]*)");
    private static final Pattern STRING_LIT = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern REQ_METHOD = Pattern.compile("RequestMethod\\s*\\.\\s*([A-Z]+)");
    private static final Pattern METHOD_NAME = Pattern.compile("(?<![@\\w])([A-Za-z_]\\w*)\\s*\\(");

    private EndpointParser() {
    }

    private record Ann(String name, String args, int start, int end) {
        String simpleName() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }

    public static List<Endpoint> parse(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] masked = mask(text);
        String code = masked[0];  // 주석 제거, 문자열 유지
        String bare = masked[1];  // 주석·문자열 내용 제거
        int[] lineStarts = lineStarts(text);

        // 1) 어노테이션 수집
        List<Ann> anns = new ArrayList<>();
        Matcher m = ANNOTATION.matcher(bare);
        while (m.find()) {
            String name = m.group(1);
            if (name.equals("interface")) {
                continue; // @interface 선언
            }
            int end = m.end();
            String args = null;
            int i = skipWs(bare, end);
            if (i < bare.length() && bare.charAt(i) == '(') {
                int close = matchParen(bare, i);
                if (close < 0) {
                    continue;
                }
                args = code.substring(i + 1, close);
                end = close + 1;
            }
            anns.add(new Ann(name, args, m.start(), end));
        }

        // 2) 사이에 공백만 있는 어노테이션끼리 묶기
        List<List<Ann>> chains = new ArrayList<>();
        List<Ann> cur = null;
        for (Ann a : anns) {
            if (cur != null && onlyWs(bare, cur.get(cur.size() - 1).end, a.start)) {
                cur.add(a);
            } else {
                cur = new ArrayList<>();
                cur.add(a);
                chains.add(cur);
            }
        }

        // 3) 묶음마다 대상(클래스 / 메서드) 판단
        List<Endpoint> result = new ArrayList<>();
        String classPrefix = "";
        for (List<Ann> chain : chains) {
            int after = chain.get(chain.size() - 1).end;
            String target = targetKind(bare, after);
            Ann mapping = null;
            for (Ann a : chain) {
                if (isMapping(a.simpleName())) {
                    mapping = a;
                    break;
                }
            }
            if (target.equals("class")) {
                if (mapping != null && mapping.simpleName().equals("RequestMapping")) {
                    List<String> paths = paths(mapping.args);
                    classPrefix = paths.isEmpty() ? "" : paths.get(0);
                } else if (mapping == null) {
                    // 클래스 수준 매핑 없는 새 클래스
                    boolean hasController = chain.stream().anyMatch(a -> a.simpleName().endsWith("Controller"));
                    if (hasController) {
                        classPrefix = "";
                    }
                }
                continue;
            }
            if (!target.equals("method") || mapping == null) {
                continue;
            }
            int[] body = methodBody(bare, after);
            int startLine = lineOf(lineStarts, chain.get(0).start);
            int endLine = lineOf(lineStarts, body[1]);
            String sig = bare.substring(after, Math.min(bare.length(), body[0] + 1));
            String methodName = "";
            Matcher nm = METHOD_NAME.matcher(sig);
            while (nm.find()) {
                String id = nm.group(1);
                if (!isKeyword(id)) {
                    methodName = id;
                    break;
                }
            }
            List<String> methods = httpMethods(mapping);
            List<String> paths = paths(mapping.args);
            if (paths.isEmpty()) {
                paths = List.of("");
            }
            for (String httpMethod : methods) {
                for (String p : paths) {
                    String full = joinPath(classPrefix, p);
                    result.add(new Endpoint(httpMethod + " " + full, httpMethod, full, startLine, endLine, methodName));
                }
            }
        }
        return result;
    }

    // ───────── 도우미 ─────────

    private static boolean isMapping(String simple) {
        return switch (simple) {
            case "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping" -> true;
            default -> false;
        };
    }

    private static List<String> httpMethods(Ann a) {
        String s = a.simpleName();
        if (!s.equals("RequestMapping")) {
            return List.of(s.substring(0, s.length() - "Mapping".length()).toUpperCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        if (a.args != null) {
            Matcher m = REQ_METHOD.matcher(a.args);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out.isEmpty() ? List.of("ANY") : out;
    }

    /** 어노테이션 인자에서 경로들을 꺼낸다. value=, path=, 첫 위치 인자, 배열을 처리한다. */
    static List<String> paths(String args) {
        List<String> out = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return out;
        }
        String a = args.trim();
        String target = null;
        Matcher named = Pattern.compile("(?:^|,)\\s*(value|path)\\s*=\\s*").matcher(a);
        if (named.find()) {
            target = a.substring(named.end());
        } else if (!a.matches("(?s)^\\s*\\w+\\s*=.*")) {
            target = a; // 첫 위치 인자
        }
        if (target == null) {
            return out;
        }
        target = target.trim();
        if (target.startsWith("{")) {
            int close = target.indexOf('}');
            String inner = close > 0 ? target.substring(1, close) : target.substring(1);
            Matcher sm = STRING_LIT.matcher(inner);
            while (sm.find()) {
                out.add(unescape(sm.group(1)));
            }
            if (out.isEmpty() && !inner.isBlank()) {
                out.add("[" + inner.trim() + "]");
            }
            return out;
        }
        if (target.startsWith("\"")) {
            Matcher sm = STRING_LIT.matcher(target);
            if (sm.find() && sm.start() == 0) {
                // "a" + "b" 같은 단순 연결
                StringBuilder sb = new StringBuilder(unescape(sm.group(1)));
                String rest = target.substring(sm.end());
                Matcher cont = Pattern.compile("^\\s*\\+\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(rest);
                while (cont.find()) {
                    sb.append(unescape(cont.group(1)));
                    rest = rest.substring(cont.end());
                    cont = Pattern.compile("^\\s*\\+\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(rest);
                }
                out.add(sb.toString());
            }
            return out;
        }
        // 상수 등 해석할 수 없는 값
        String expr = target.split(",")[0].trim();
        if (!expr.isEmpty()) {
            out.add("[" + expr + "]");
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String joinPath(String prefix, String path) {
        String p = (prefix == null ? "" : prefix.trim());
        String q = (path == null ? "" : path.trim());
        String joined;
        if (p.isEmpty()) {
            joined = q;
        } else if (q.isEmpty()) {
            joined = p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        } else {
            joined = (p.endsWith("/") ? p.substring(0, p.length() - 1) : p) + (q.startsWith("/") ? q : "/" + q);
        }
        if (!joined.startsWith("/") && !joined.startsWith("[")) {
            joined = "/" + joined;
        }
        joined = joined.replaceAll("(?<!:)/{2,}", "/");
        return joined.isEmpty() ? "/" : joined;
    }

    /** 어노테이션 묶음 뒤에 오는 선언이 클래스인지 메서드인지 필드인지. */
    private static String targetKind(String bare, int from) {
        int i = from;
        StringBuilder word = new StringBuilder();
        int angle = 0;
        while (i < bare.length()) {
            char c = bare.charAt(i);
            if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            } else if (angle <= 0 && (c == '(' || c == '{' || c == ';' || c == '=')) {
                String w = word.toString();
                if (w.matches("(?s).*\\b(class|interface|enum|record)\\b.*")) {
                    return "class";
                }
                return c == '(' ? "method" : "other";
            }
            word.append(c);
            if (word.length() > 2000) {
                break;
            }
            i++;
        }
        return "other";
    }

    /** 반환: {여는 중괄호(또는 ;) 위치, 닫는 중괄호(또는 ;) 위치} */
    private static int[] methodBody(String bare, int from) {
        int paren = 0;
        int i = from;
        while (i < bare.length()) {
            char c = bare.charAt(i);
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (paren == 0 && c == ';') {
                return new int[]{i, i};
            } else if (paren == 0 && c == '{') {
                int depth = 0;
                for (int j = i; j < bare.length(); j++) {
                    char d = bare.charAt(j);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                        if (depth == 0) {
                            return new int[]{i, j};
                        }
                    }
                }
                return new int[]{i, bare.length() - 1};
            }
            i++;
        }
        return new int[]{bare.length() - 1, bare.length() - 1};
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean onlyWs(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKeyword(String s) {
        return switch (s) {
            case "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throws" -> true;
            default -> false;
        };
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** offset이 속한 줄번호 (1부터) */
    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    /**
     * 같은 길이의 문자열 두 개를 만든다. [0]은 주석을 공백으로 바꾼 것, [1]은 거기에 더해 문자열·문자 리터럴 내용까지 공백으로 바꾼 것.
     * 줄바꿈 위치는 그대로 둬서 줄번호 계산이 원문과 같다.
     */
    static String[] mask(String t) {
        char[] code = t.toCharArray();
        char[] bare = t.toCharArray();
        final int normal = 0;
        final int line = 1;
        final int block = 2;
        final int str = 3;
        final int chr = 4;
        final int textBlock = 5;
        int st = normal;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            char n = i + 1 < t.length() ? t.charAt(i + 1) : '\0';
            switch (st) {
                case normal -> {
                    if (c == '/' && n == '/') {
                        st = line;
                        code[i] = bare[i] = ' ';
                    } else if (c == '/' && n == '*') {
                        st = block;
                        code[i] = bare[i] = ' ';
                    } else if (c == '"' && t.startsWith("\"\"\"", i)) {
                        st = textBlock;
                        i += 2;
                    } else if (c == '"') {
                        st = str;
                    } else if (c == '\'') {
                        st = chr;
                    }
                }
                case line -> {
                    if (c == '\n') {
                        st = normal;
                    } else {
                        code[i] = bare[i] = ' ';
                    }
                }
                case block -> {
                    if (c == '*' && n == '/') {
                        code[i] = bare[i] = ' ';
                        code[i + 1] = bare[i + 1] = ' ';
                        i++;
                        st = normal;
                    } else if (c != '\n') {
                        code[i] = bare[i] = ' ';
                    }
                }
                case str, chr -> {
                    char quote = st == str ? '"' : '\'';
                    if (c == '\\') {
                        bare[i] = ' ';
                        if (i + 1 < t.length() && t.charAt(i + 1) != '\n') {
                            bare[i + 1] = ' ';
                            i++;
                        }
                    } else if (c == quote || c == '\n') {
                        st = normal;
                    } else {
                        bare[i] = ' ';
                    }
                }
                case textBlock -> {
                    if (c == '"' && t.startsWith("\"\"\"", i)) {
                        i += 2;
                        st = normal;
                    } else if (c != '\n') {
                        bare[i] = ' ';
                    }
                }
                default -> {
                }
            }
        }
        return new String[]{new String(code), new String(bare)};
    }
}
