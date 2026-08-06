package io.github.monssifechadli99.transactiq.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesSafeRequestIdForTheDurationOfTheRequest() throws Exception {
        String requestId = "ad68b682-3969-42cb-9f9b-3c991ab97d3e";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInsideChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                requestIdInsideChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(requestIdInsideChain).hasValue(requestId);
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
                .isEqualTo(requestId);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeHeaderWithoutRetainingItsValue() throws Exception {
        String unsafeValue = "analyst-question=show private evidence\nsecond-line";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/authorizations");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, unsafeValue);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

        String generatedRequestId = response.getHeader(RequestCorrelationFilter.HEADER_NAME);
        assertThat(generatedRequestId)
                .isNotBlank()
                .doesNotContain("analyst", "question", "evidence", "second-line");
        assertThat(generatedRequestId).matches("[0-9a-f-]{36}");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }
}
