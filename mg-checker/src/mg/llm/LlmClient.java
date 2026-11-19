package mg.llm;

import mg.check.FileContext;

/** LLM 호출 인터페이스. 실제 공급자(Anthropic, OpenAI)와 데모용 mock이 구현한다. */
public interface LlmClient {

    /**
     * @param prompt 보낼 지시문 전체 (LLM은 앞선 대화를 기억하지 않으므로 매번 전부 보낸다)
     * @param ctx    mock이 쓰는 구조화된 입력. 실제 공급자는 쓰지 않는다.
     */
    LlmResponse complete(String prompt, FileContext ctx) throws LlmException;

    /** 화면과 기록에 표시할 이름. 예: "anthropic / claude-sonnet-5-5" */
    String label();
}
