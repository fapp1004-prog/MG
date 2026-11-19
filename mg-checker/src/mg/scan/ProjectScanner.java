package mg.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import mg.util.Texts;

/**
 * [규칙] 프로젝트 폴더에서 컨트롤러 파일과 권한 정책 문서 후보를 골라낸다.
 * 서비스, 엔티티, 설정, 테스트 코드는 LLM에 보내지 않는다.
 */
public final class ProjectScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".settings", ".mvn",
            "target", "build", "out", "bin", "node_modules", "dist", "runs");
    private static final Pattern CONTROLLER = Pattern.compile(
            "(?m)^\\s*@(?:org\\.springframework\\.(?:web\\.bind\\.annotation|stereotype)\\.)?(?:Rest)?Controller\\b(?!Advice)");
    private static final long MAX_JAVA_BYTES = 300_000;
    private static final long MAX_DOC_BYTES = 100_000;
    private static final int MAX_VISITED = 30_000;

    private ProjectScanner() {
    }

    public record ControllerFile(String path, int lineCount, List<Endpoint> endpoints) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("lineCount", lineCount);
            List<Object> eps = new ArrayList<>();
            for (Endpoint e : endpoints) {
                eps.add(e.toMap());
            }
            m.put("endpoints", eps);
            return m;
        }
    }

    public record ScanResult(String root, List<ControllerFile> controllers, List<String> policyFiles,
                             int visitedFiles, boolean truncated) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("root", root);
            List<Object> cs = new ArrayList<>();
            for (ControllerFile c : controllers) {
                cs.add(c.toMap());
            }
            m.put("controllers", cs);
            m.put("policyFiles", policyFiles);
            m.put("visitedFiles", visitedFiles);
            m.put("truncated", truncated);
            return m;
        }
    }

    public static Path checkRoot(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("프로젝트 폴더 경로를 입력하세요.");
        }
        String cleaned = path.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        Path root = Paths.get(cleaned);
        if (!Files.isDirectory(root)) {
            throw new IOException("폴더가 없습니다: " + cleaned);
        }
        return root.toRealPath();
    }

    public static ScanResult scan(Path root) throws IOException {
        List<ControllerFile> controllers = new ArrayList<>();
        List<String> policies = new ArrayList<>();
        int[] visited = {0};
        boolean[] truncated = {false};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String name = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = Texts.relative(root, dir);
                    if (rel.endsWith("src/test") || rel.equals("src/test")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > MAX_VISITED) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".java") && attrs.size() <= MAX_JAVA_BYTES) {
                    String src = Texts.read(file);
                    if (CONTROLLER.matcher(src).find()) {
                        List<Endpoint> eps = EndpointParser.parse(src);
                        int lines = src.split("\n", -1).length;
                        controllers.add(new ControllerFile(Texts.relative(root, file), lines, eps));
                    }
                } else if ((lower.endsWith(".md") || lower.endsWith(".txt")) && attrs.size() <= MAX_DOC_BYTES) {
                    if (lower.contains("정책") || lower.contains("policy") || lower.contains("권한")
                            || lower.contains("authz") || lower.contains("permission")) {
                        policies.add(Texts.relative(root, file));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        controllers.sort((a, b) -> a.path().compareTo(b.path()));
        policies.sort(String::compareTo);
        return new ScanResult(root.toString(), controllers, policies, visited[0], truncated[0]);
    }
}
