package mg;

import java.nio.file.Path;
import java.nio.file.Paths;

import mg.llm.LlmClient;
import mg.llm.LlmClients;
import mg.web.Server;

/**
 * MG (Missing Guard) — 배포 전 권한 검사 누락 점검기.
 * 실행: run.bat (Windows) 또는 run.sh. 브라우저에서 http://127.0.0.1:8090 을 연다.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getProperty("mg.home", ".")).toAbsolutePath().normalize();
        Config cfg;
        try {
            cfg = Config.load(base);
        } catch (IllegalArgumentException e) {
            // 설정 값 오타 등은 스택 트레이스 대신 고칠 곳만 알려 준다
            System.out.println();
            System.out.println("  [MG] mg.properties 설정 오류: " + e.getMessage());
            System.out.println("       파일: " + base.resolve("mg.properties"));
            System.exit(1);
            return;
        }
        LlmClient llm = LlmClients.create(cfg);
        Server server = new Server(cfg, llm);
        try {
            server.start();
        } catch (java.net.BindException e) {
            System.out.println();
            System.out.println("  포트 " + cfg.port + "를 이미 다른 프로그램이 쓰고 있습니다.");
            System.out.println("  MG가 이미 켜져 있다면 그 창을 쓰거나 닫고 다시 실행하세요.");
            System.out.println("  아니면 mg.properties의 port= 값을 다른 숫자(예: 8091)로 바꾸세요.");
            System.exit(1);
        }

        System.out.println();
        System.out.println("  MG · Missing Guard — 배포 전 권한 검사 누락 점검기");
        System.out.println("  ------------------------------------------------");
        System.out.println("  주소   : http://127.0.0.1:" + cfg.port);
        System.out.println("  모델   : " + llm.label());
        System.out.println("  API 키 : " + (cfg.provider.equals("mock") ? "필요 없음 (mock)"
                : cfg.apiKey.isBlank() ? "없음 → mg.properties에 api.key를 넣으세요" : "설정됨"));
        System.out.println("  기록   : " + cfg.runsDir);
        System.out.println("  로그인 : " + cfg.loginUser + " / " + (cfg.passwordGenerated
                ? cfg.loginPassword + "   (이번 실행용 임시 비밀번호. 고정하려면 mg.properties의 login.password)"
                : "mg.properties의 login.password"));
        System.out.println("  끄려면 이 창에서 Ctrl+C");
        System.out.println();
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
