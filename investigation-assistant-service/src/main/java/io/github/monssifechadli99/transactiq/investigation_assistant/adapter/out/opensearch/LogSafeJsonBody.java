package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import org.springframework.http.StreamingHttpOutputMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams a JSON request without handing a data-bearing object to RestClient's message
 * converter logging. Its representation is deliberately constant so accidental logging
 * by an interceptor cannot reveal evidence text, questions, vectors, or private metadata.
 */
final class LogSafeJsonBody implements StreamingHttpOutputMessage.Body {

    private static final String REDACTED_REPRESENTATION = "LogSafeJsonBody[content=<redacted>]";

    private final ObjectMapper mapper;
    private final Object value;

    private LogSafeJsonBody(ObjectMapper mapper, Object value) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.value = Objects.requireNonNull(value, "value");
    }

    static LogSafeJsonBody of(ObjectMapper mapper, Object value) {
        return new LogSafeJsonBody(mapper, value);
    }

    @Override
    public void writeTo(OutputStream outputStream) throws IOException {
        mapper.writeValue(outputStream, value);
    }

    @Override
    public String toString() {
        return REDACTED_REPRESENTATION;
    }
}
