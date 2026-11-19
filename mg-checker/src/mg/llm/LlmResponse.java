package mg.llm;

/**
 * @param text      모델이 돌려준 본문
 * @param truncated 길이 제한으로 잘렸는지
 */
public record LlmResponse(String text, boolean truncated) {
}
