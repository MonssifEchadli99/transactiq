package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;

public interface ChatGenerationPort {
    GeneratedAnswerDraft generate(ChatGenerationRequest request);
}
