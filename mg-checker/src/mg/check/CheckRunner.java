package mg.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import mg.llm.LlmClient;
import mg.llm.LlmException;
import mg.llm.LlmResponse;
import mg.scan.Endpoint;
import mg.scan.EndpointParser;
import mg.scan.PolicyParser;
import mg.scan.PolicyParser.Policy;
import mg.util.Json;
import mg.util.Texts;

/**
 * 점검 실행. 선택한 컨트롤러 파일마다 LLM을 한 번씩 부르고, 결과를 규칙으로 검사한 뒤 합친다.
 * 지시문, 응답 원문, 결과는 runs/&lt;실행ID&gt;/ 아래에 그대로 남는다 (나중에 다시 보고 검증할 수 있게).
 */
public final class CheckRunner {

    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final LlmClient llm;
    private final Path runsDir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mg-check");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public CheckRunner(LlmClient llm, Path runsDir) {
        this.llm = llm;
        this.runsDir = runsDir;
    }

    /** 진행 중이거나 끝난 실행 하나. */
    public static final class Run {
        public final String id;
        public final Path dir;
        public volatile String status = "queued"; // queued | running | done | error
        public volatile int done;
        public volatile int total;
        public volatile String current = "";
        public volatile String error;
        public volatile Map<String, Object> result;
        public final List<Map<String, Object>> fileStatus = new ArrayList<>();
        public final Map<String, Object> verdicts = new ConcurrentHashMap<>();

        Run(String id, Path dir) {
            this.id = id;
            this.dir = dir;
        }

        public synchronized Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("status", status);
            m.put("done", done);
            m.put("total", total);
            m.put("current", current);
            m.put("error", error);
            List<Object> fs = new ArrayList<>();
            for (Map<String, Object> f : fileStatus) {
                fs.add(new LinkedHashMap<>(f));
            }
            m.put("files", fs);
            m.put("result", result);
            m.put("verdicts", new LinkedHashMap<>(verdicts));
            return m;
        }

        synchronized void setFile(int i, String state, String message) {
            fileStatus.get(i).put("state", state);
            fileStatus.get(i).put("message", message);
        }
    }

    public Run start(Path root, List<String> files, String policyText) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("점검할 컨트롤러를 하나 이상 고르세요.");
        }
        List<Policy> policies = PolicyParser.parse(policyText);
        if (policies.isEmpty()) {
            throw new IOException("권한 정책이 비어 있습니다. 정책 문서를 고르거나 직접 입력하세요.");
        }
        // 파일 경로를 먼저 다 확인한다 (프로젝트 밖 경로 거부)
        List<Path> resolved = new ArrayList<>();
        for (String f : files) {
            resolved.add(Texts.resolveInside(root, f));
        }
        String id = LocalDateTime.now().format(ID_FMT) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff));
        Path dir = runsDir.resolve(id);
        Files.createDirectories(dir.resolve("prompts"));
        Files.createDirectories(dir.resolve("raw"));
        Run run = new Run(id, dir);
        run.total = files.size();
        for (String f : files) {
            Map<String, Object> fs = new LinkedHashMap<>();
            fs.put("path", f);
            fs.put("state", "waiting");
            fs.put("message", "");
            run.fileStatus.add(fs);
        }
        runs.put(id, run);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("startedAt", LocalDateTime.now().toString());
        meta.put("root", root.toString());
        meta.put("files", files);
        meta.put("model", llm.label());
        meta.put("policyText", policyText);
        write(dir.resolve("meta.json"), Json.pretty(meta));

        pool.submit(() -> execute(run, root, files, resolved, policies, meta));
        return run;
    }

    public Run get(String id) {
        return runs.get(id);
    }

    private void execute(Run run, Path root, List<String> files, List<Path> resolved, List<Policy> policies,
                         Map<String, Object> meta) {
        run.status = "running";
        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> fileResults = new ArrayList<>();
        int downgrades = 0;
        int formatRetries = 0;
        boolean aborted = false;

        for (int i = 0; i < files.size(); i++) {
            String rel = files.get(i);
            run.current = rel;
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("path", rel);
            fileResults.add(fr);
            if (aborted) {
                run.setFile(i, "skipped", "앞선 치명적 오류로 건너뜀");
                fr.put("state", "skipped");
                continue;
            }
            run.setFile(i, "running", "");
            try {
                String src = Texts.read(resolved.get(i));
                List<String> lines = Arrays.asList(src.split("\n", -1));
                List<Endpoint> eps = EndpointParser.parse(src);
                fr.put("lines", lines);
                List<Object> epMaps = new ArrayList<>();
                eps.forEach(e -> epMaps.add(e.toMap()));
                fr.put("endpoints", epMaps);
                if (eps.isEmpty()) {
                    run.setFile(i, "done", "API가 없어 LLM을 부르지 않음");
                    fr.put("state", "done");
                    fr.put("message", "API 없음");
                    run.done++;
                    continue;
                }
                FileContext ctx = new FileContext(rel, lines, eps, policies);
                String prompt = PromptBuilder.build(ctx);
                String tag = String.format("%02d-%s", i + 1, safeName(rel));
                write(run.dir.resolve("prompts").resolve(tag + ".txt"), prompt);

                LlmResponse res = llm.complete(prompt, ctx);
                write(run.dir.resolve("raw").resolve(tag + ".txt"), res.text());
                ResultValidator.FileOutcome out;
                try {
                    out = ResultValidator.validate(res.text(), ctx);
                } catch (FormatException fe) {
                    // 형식이 깨지면 한 번만 다시 요청한다
                    formatRetries++;
                    String retryPrompt = PromptBuilder.retry(prompt, fe.getMessage());
                    LlmResponse res2 = llm.complete(retryPrompt, ctx);
                    write(run.dir.resolve("raw").resolve(tag + "-retry.txt"), res2.text());
                    res = res2;
                    out = ResultValidator.validate(res2.text(), ctx);
                    notes.add(rel + ": 첫 응답의 형식이 깨져 한 번 다시 요청함 (" + fe.getMessage() + ")");
                }
                if (res.truncated()) {
                    notes.add(rel + ": 응답이 길이 제한으로 잘렸을 수 있음. 파일을 나눠 점검하세요.");
                }
                cards.addAll(out.cards());
                for (String n : out.notes()) {
                    notes.add(rel + ": " + n);
                }
                downgrades += out.downgrades();
                run.setFile(i, "done", out.cards().size() + "개 판단");
                fr.put("state", "done");
            } catch (LlmException e) {
                run.setFile(i, "error", e.getMessage());
                fr.put("state", "error");
                fr.put("message", e.getMessage());
                notes.add(rel + ": " + e.getMessage());
                if (e.fatal()) {
                    aborted = true;
                    run.error = e.getMessage();
                }
            } catch (FormatException e) {
                run.setFile(i, "error", "다시 요청해도 형식이 맞지 않음: " + e.getMessage());
                fr.put("state", "error");
                fr.put("message", "형식 오류: " + e.getMessage());
                notes.add(rel + ": 다시 요청해도 응답 형식이 맞지 않아 이 파일은 판단하지 못함");
            } catch (Exception e) {
                run.setFile(i, "error", e.toString());
                fr.put("state", "error");
                fr.put("message", e.toString());
                notes.add(rel + ": " + e);
            }
            run.done++;
        }

        // [규칙] 합치기: 짝 없는 정책, 짝 없는 API
        Set<String> policiesWithCard = new HashSet<>();
        Set<String> endpointsWithCard = new HashSet<>();
        for (Map<String, Object> c : cards) {
            policiesWithCard.add(String.valueOf(c.get("policyId")));
            endpointsWithCard.add(c.get("file") + "|" + c.get("endpoint"));
        }
        boolean anyFileOk = fileResults.stream().anyMatch(f -> "done".equals(f.get("state")));
        if (anyFileOk) {
            for (Policy p : policies) {
                if (!policiesWithCard.contains(p.id())) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("file", null);
                    c.put("policyId", p.id());
                    c.put("policyText", p.text());
                    c.put("endpoint", null);
                    c.put("status", "nopair");
                    c.put("conditions", List.of());
                    cards.add(c);
                }
            }
        }
        for (Map<String, Object> fr : fileResults) {
            if (!"done".equals(fr.get("state")) || fr.get("endpoints") == null) {
                continue;
            }
            for (Object o : Json.asList(fr.get("endpoints"))) {
                Map<String, Object> e = Json.asMap(o);
                String key = String.valueOf(e.get("key"));
                if (!endpointsWithCard.contains(fr.get("path") + "|" + key)) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("file", fr.get("path"));
                    c.put("policyId", null);
                    c.put("policyText", null);
                    c.put("endpoint", key);
                    c.put("start", e.get("start"));
                    c.put("end", e.get("end"));
                    c.put("status", "nomatch");
                    c.put("conditions", List.of());
                    cards.add(c);
                }
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("missing", "unsure", "ok", "nomatch", "nopair")) {
            counts.put(s, cards.stream().filter(c -> s.equals(c.get("status"))).count());
        }
        int endpointTotal = 0;
        for (Map<String, Object> fr : fileResults) {
            List<Object> eps = Json.asList(fr.get("endpoints"));
            endpointTotal += eps == null ? 0 : eps.size();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.id);
        result.put("startedAt", meta.get("startedAt"));
        result.put("finishedAt", LocalDateTime.now().toString());
        result.put("root", root.toString());
        result.put("model", llm.label());
        List<Object> pol = new ArrayList<>();
        policies.forEach(p -> pol.add(p.toMap()));
        result.put("policies", pol);
        result.put("endpointCount", endpointTotal);
        result.put("files", fileResults);
        result.put("cards", cards);
        result.put("counts", counts);
        result.put("downgrades", downgrades);
        result.put("formatRetries", formatRetries);
        result.put("notes", notes);
        run.result = result;
        try {
            write(run.dir.resolve("result.json"), Json.pretty(result));
            write(run.dir.resolve("report.md"), Report.markdown(result, run.verdicts));
        } catch (IOException e) {
            notes.add("결과 저장 실패: " + e.getMessage());
        }
        run.current = "";
        run.status = (aborted && !anyFileOk) ? "error" : "done";
    }

    public void saveVerdict(Run run, String key, String value) throws IOException {
        if (value == null || value.isBlank()) {
            run.verdicts.remove(key);
        } else {
            run.verdicts.put(key, value);
        }
        write(run.dir.resolve("verdicts.json"), Json.pretty(new LinkedHashMap<>(run.verdicts)));
        if (run.result != null) {
            write(run.dir.resolve("report.md"), Report.markdown(run.result, run.verdicts));
        }
    }

    /** 디스크에 남은 지난 실행을 불러온다 (서버를 다시 켠 뒤에도 보이도록). */
    public Run load(String id) throws IOException {
        Run r = runs.get(id);
        if (r != null) {
            return r;
        }
        if (!id.matches("[0-9A-Za-z-]{1,40}")) {
            return null;
        }
        Path dir = runsDir.resolve(id);
        Path res = dir.resolve("result.json");
        if (!Files.isRegularFile(res)) {
            return null;
        }
        Run run = new Run(id, dir);
        run.result = Json.asMap(Json.parse(Files.readString(res, StandardCharsets.UTF_8)));
        run.status = "done";
        Path v = dir.resolve("verdicts.json");
        if (Files.isRegularFile(v)) {
            Map<String, Object> vm = Json.asMap(Json.parse(Files.readString(v, StandardCharsets.UTF_8)));
            if (vm != null) {
                run.verdicts.putAll(vm);
            }
        }
        if (run.result != null) {
            List<Object> fl = Json.asList(run.result.get("files"));
            run.total = fl == null ? 0 : fl.size();
            run.done = run.total;
        }
        runs.put(id, run);
        return run;
    }

    public List<Map<String, Object>> history() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(runsDir)) {
            return out;
        }
        try (var stream = Files.list(runsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted((a, b) -> b.getFileName().compareTo(a.getFileName())).toList();
            for (Path d : dirs) {
                Path res = d.resolve("result.json");
                if (!Files.isRegularFile(res)) {
                    continue;
                }
                try {
                    Map<String, Object> r = Json.asMap(Json.parse(Files.readString(res, StandardCharsets.UTF_8)));
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("id", d.getFileName().toString());
                    h.put("startedAt", r.get("startedAt"));
                    h.put("model", r.get("model"));
                    h.put("finishedAt", r.get("finishedAt"));
                    h.put("counts", r.get("counts"));
                    h.put("root", r.get("root"));
                    h.put("endpointCount", r.get("endpointCount"));
                    List<Object> fl = Json.asList(r.get("files"));
                    List<Object> fileNames = new ArrayList<>();
                    if (fl != null) {
                        for (Object fo : fl) {
                            fileNames.add(Json.asMap(fo).get("path"));
                        }
                    }
                    h.put("files", fileNames);
                    long need = 0;
                    List<Object> cl = Json.asList(r.get("cards"));
                    if (cl != null) {
                        for (Object co : cl) {
                            Object st = Json.asMap(co).get("status");
                            if ("missing".equals(st) || "unsure".equals(st) || "ok".equals(st)) {
                                need++;
                            }
                        }
                    }
                    long vOk = 0;
                    long vFp = 0;
                    Path vf = d.resolve("verdicts.json");
                    if (Files.isRegularFile(vf)) {
                        Map<String, Object> vm = Json.asMap(Json.parse(Files.readString(vf, StandardCharsets.UTF_8)));
                        if (vm != null) {
                            for (Object v : vm.values()) {
                                if ("ok".equals(v)) {
                                    vOk++;
                                } else if ("fp".equals(v)) {
                                    vFp++;
                                }
                            }
                        }
                    }
                    h.put("reviewNeed", need);
                    h.put("reviewOk", vOk);
                    h.put("reviewFp", vFp);
                    out.add(h);
                } catch (Exception ignored) {
                    // 깨진 기록은 건너뜀
                }
                if (out.size() >= 200) {
                    break;
                }
            }
        }
        return out;
    }

    private static String safeName(String rel) {
        String n = rel.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        String parent = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
        String last = parent.contains("/") ? parent.substring(parent.lastIndexOf('/') + 1) : parent;
        String s = (last.isEmpty() ? "" : last + "_") + n;
        return s.replaceAll("[^0-9A-Za-z._-]", "_");
    }

    private static void write(Path p, String content) throws IOException {
        Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
