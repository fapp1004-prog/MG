package mg.check;

/** LLM 응답이 정해진 JSON 형식이 아닐 때. */
public class FormatException extends Exception {
    public FormatException(String message) {
        super(message);
    }
}
