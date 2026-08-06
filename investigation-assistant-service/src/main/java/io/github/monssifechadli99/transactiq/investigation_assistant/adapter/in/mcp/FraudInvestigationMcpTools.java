package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Answer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Citation;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Finding;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.RelatedCase;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Retrieval;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Source;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationAnswerService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.AnswerGenerationUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingProviderUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.FocalEvidenceNotFoundException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP boundary over the existing 6A retrieval and 6B grounded-answer services.
 * Tool failures are converted to fixed codes so raw input, evidence, and provider details
 * never become protocol errors.
 */
@Component
public final class FraudInvestigationMcpTools {

    public static final String RETRIEVE_TOOL_NAME = "retrieve_fraud_case_evidence";
    public static final String ANSWER_TOOL_NAME = "answer_fraud_investigation_question";

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_RELATED_CASES = 5;
    private static final String INVALID_REQUEST = "INVALID_INVESTIGATION_REQUEST";
    private static final String EVIDENCE_NOT_FOUND = "FOCAL_EVIDENCE_NOT_FOUND";
    private static final String RETRIEVAL_UNAVAILABLE = "INVESTIGATION_RETRIEVAL_UNAVAILABLE";
    private static final String ANSWER_UNAVAILABLE = "INVESTIGATION_ANSWER_UNAVAILABLE";

    private final InvestigationRetrievalService retrievalService;
    private final InvestigationAnswerService answerService;
    private final McpInvestigationMapper mapper;

    public FraudInvestigationMcpTools(
            InvestigationRetrievalService retrievalService,
            InvestigationAnswerService answerService) {
        this.retrievalService = retrievalService;
        this.answerService = answerService;
        this.mapper = new McpInvestigationMapper();
    }

    @McpTool(
            name = RETRIEVE_TOOL_NAME,
            description = "Retrieve safe focal and related evidence for advisory fraud investigation. "
                    + "This read-only tool never changes a case.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult retrieveFraudCaseEvidence(
            @McpToolParam(description = "Fraud case UUID", required = true) String caseId,
            @McpToolParam(description = "Analyst investigation question", required = true) String question) {
        McpInvestigationRequest request = validate(caseId, question);
        if (request == null) {
            return error(INVALID_REQUEST);
        }
        try {
            Retrieval response = mapper.toRetrieval(
                    retrievalService.retrieve(request.caseId(), request.question(), MAX_RELATED_CASES));
            return success("INVESTIGATION_EVIDENCE_RETRIEVED", retrievalContent(response));
        } catch (FocalEvidenceNotFoundException ignored) {
            return error(EVIDENCE_NOT_FOUND);
        } catch (EmbeddingProviderUnavailableException | EvidenceStoreUnavailableException ignored) {
            return error(RETRIEVAL_UNAVAILABLE);
        } catch (RuntimeException ignored) {
            return error(RETRIEVAL_UNAVAILABLE);
        }
    }

    @McpTool(
            name = ANSWER_TOOL_NAME,
            description = "Return an advisory answer grounded only in retrieved fraud-case evidence. "
                    + "This read-only tool never claims, resolves, approves, blocks, assigns, or changes a case.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult answerFraudInvestigationQuestion(
            @McpToolParam(description = "Fraud case UUID", required = true) String caseId,
            @McpToolParam(description = "Analyst investigation question", required = true) String question) {
        McpInvestigationRequest request = validate(caseId, question);
        if (request == null) {
            return error(INVALID_REQUEST);
        }
        try {
            Answer response = mapper.toAnswer(answerService.answer(request.caseId(), request.question()));
            return success("INVESTIGATION_ANSWER_READY", answerContent(response));
        } catch (FocalEvidenceNotFoundException ignored) {
            return error(EVIDENCE_NOT_FOUND);
        } catch (EmbeddingProviderUnavailableException | EvidenceStoreUnavailableException ignored) {
            return error(RETRIEVAL_UNAVAILABLE);
        } catch (AnswerGenerationUnavailableException ignored) {
            return error(ANSWER_UNAVAILABLE);
        } catch (RuntimeException ignored) {
            return error(ANSWER_UNAVAILABLE);
        }
    }

    private static McpInvestigationRequest validate(String caseId, String question) {
        if (caseId == null || caseId.isBlank() || question == null || question.isBlank()
                || question.length() > MAX_QUESTION_LENGTH) {
            return null;
        }
        try {
            return new McpInvestigationRequest(UUID.fromString(caseId).toString(), question);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CallToolResult success(String status, Map<String, Object> content) {
        return CallToolResult.builder()
                .isError(false)
                .addTextContent(status)
                .structuredContent(content)
                .build();
    }

    private static CallToolResult error(String code) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent(code)
                .structuredContent(Map.of("code", code))
                .build();
    }

    private static Map<String, Object> retrievalContent(Retrieval response) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("caseId", response.caseId());
        content.put("focalSources", response.focalSources().stream()
                .map(FraudInvestigationMcpTools::sourceContent)
                .toList());
        content.put("relatedCases", response.relatedCases().stream()
                .map(FraudInvestigationMcpTools::relatedCaseContent)
                .toList());
        return Map.copyOf(content);
    }

    private static Map<String, Object> sourceContent(Source source) {
        return Map.of(
                "sourceId", source.sourceId(),
                "sourceType", source.sourceType(),
                "caseId", source.caseId(),
                "excerpt", source.excerpt());
    }

    private static Map<String, Object> relatedCaseContent(RelatedCase relatedCase) {
        return Map.of(
                "caseId", relatedCase.caseId(),
                "sources", relatedCase.sources().stream()
                        .map(FraudInvestigationMcpTools::sourceContent)
                        .toList());
    }

    private static Map<String, Object> answerContent(Answer response) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("caseId", response.caseId());
        content.put("summary", response.summary());
        content.put("findings", response.findings().stream()
                .map(FraudInvestigationMcpTools::findingContent)
                .toList());
        content.put("recommendedChecks", response.recommendedChecks());
        content.put("groundingStatus", response.groundingStatus());
        return Map.copyOf(content);
    }

    private static Map<String, Object> findingContent(Finding finding) {
        return Map.of(
                "text", finding.text(),
                "citations", finding.citations().stream()
                        .map(FraudInvestigationMcpTools::citationContent)
                        .toList());
    }

    private static Map<String, Object> citationContent(Citation citation) {
        return Map.of(
                "sourceId", citation.sourceId(),
                "sourceType", citation.sourceType(),
                "caseId", citation.caseId(),
                "excerpt", citation.excerpt());
    }
}
