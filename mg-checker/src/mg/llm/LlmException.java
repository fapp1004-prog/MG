package mg.llm;

/** LLM 호출 실패. fatal이면 같은 실행의 나머지 파일도 멈춘다 (예: 키 오류). */
public class LlmException extends Exception {

    private final boolean fatal;

    public LlmException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public boolean fatal() {
        return fatal;
    }
}
