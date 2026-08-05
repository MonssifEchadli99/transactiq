package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

public final class FocalEvidenceNotFoundException extends RuntimeException {
    public FocalEvidenceNotFoundException(String caseId) {
        super("Focal evidence is not yet indexed for case " + caseId);
    }
}
