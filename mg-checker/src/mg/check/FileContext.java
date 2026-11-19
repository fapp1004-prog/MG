package mg.check;

import java.util.List;

import mg.scan.Endpoint;
import mg.scan.PolicyParser.Policy;

/**
 * LLM 호출 한 번에 들어가는 입력. 컨트롤러 파일 하나 단위로 나눠서 보낸다.
 *
 * @param path      프로젝트 기준 상대 경로
 * @param lines     파일의 줄 목록 (줄번호는 1부터)
 * @param endpoints 규칙이 뽑은 이 파일의 API
 * @param policies  정책 전체
 */
public record FileContext(String path, List<String> lines, List<Endpoint> endpoints, List<Policy> policies) {
}
