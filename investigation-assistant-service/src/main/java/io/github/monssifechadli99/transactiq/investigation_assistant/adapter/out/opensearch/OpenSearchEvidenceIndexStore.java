package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ProjectionIntegrityException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Projection-aware evidence write path. Each chunk carries a private snapshot
 * discriminator, and every write uses an OpenSearch sequence/primary-term CAS loop.
 * The case-evidence document is first written with a private incomplete publication
 * marker, the resolution slot is written ACTIVE or ABSENT, and only then is the evidence
 * document republished as complete. Retrieval accepts only a complete matching pair.
 */
public final class OpenSearchEvidenceIndexStore implements EvidenceIndexPort {

    private static final int MAX_ATTEMPTS = 5;
    private static final String ACTIVE = "ACTIVE";
    private static final String ABSENT = "ABSENT";

    private final RestClient client;
    private final ObjectMapper mapper;
    private final InvestigationOpenSearchProperties properties;

    public OpenSearchEvidenceIndexStore(
            RestClient client, ObjectMapper mapper, InvestigationOpenSearchProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public OptionalLong currentVersion(String sourceId) {
        Current current = read(sourceId);
        return current == null ? OptionalLong.empty() : OptionalLong.of(current.projectionVersion());
    }

    /** Compatibility entry point for focused store/retrieval fixtures. */
    @Override
    public void index(EvidenceDraft draft, float[] embedding) {
        if (draft.sourceType() != EvidenceSourceType.CASE_EVIDENCE) {
            throw new IllegalArgumentException("Standalone resolution indexing cannot publish a complete snapshot");
        }
        String integrity = legacyIntegrity(draft);
        writeActive(draft, embedding, integrity, false, false);
        writeAbsentResolution(draft.caseId(), draft.projectionVersion(), integrity);
        writeActive(draft, embedding, integrity, true, false);
    }

    @Override
    public ProjectionAssessment assessProjection(
            ValidatedProjection projection, List<EvidenceDraft> expectedDrafts) {
        ExpectedChunks expected = expectedChunks(projection, expectedDrafts);
        return assess(
                projection.snapshot().getCaseId(),
                projection.snapshot().getAggregateVersion(),
                projection.integrityDiscriminator(),
                expected.resolutionExpected());
    }

    @Override
    public void indexProjection(
            ValidatedProjection projection,
            List<EvidenceDraft> drafts,
            List<float[]> embeddings) {
        if (drafts.size() != embeddings.size()) {
            throw new IllegalArgumentException("Each evidence draft requires one embedding");
        }
        ExpectedChunks expected = expectedChunks(projection, drafts);
        if (assess(
                        projection.snapshot().getCaseId(),
                        projection.snapshot().getAggregateVersion(),
                        projection.integrityDiscriminator(),
                        expected.resolutionExpected())
                == ProjectionAssessment.NO_OP) {
            return;
        }

        WriteResult evidenceResult = writeActive(
                expected.drafts().get(EvidenceSourceType.CASE_EVIDENCE),
                embeddings.get(expected.positions().get(EvidenceSourceType.CASE_EVIDENCE)),
                projection.integrityDiscriminator(),
                false,
                expected.resolutionExpected());
        if (evidenceResult == WriteResult.STALE) {
            return;
        }

        WriteResult resolutionResult;
        if (expected.resolutionExpected()) {
            resolutionResult = writeActive(
                    expected.drafts().get(EvidenceSourceType.RESOLUTION),
                    embeddings.get(expected.positions().get(EvidenceSourceType.RESOLUTION)),
                    projection.integrityDiscriminator(),
                    null,
                    null);
        } else {
            resolutionResult = writeAbsentResolution(
                    projection.snapshot().getCaseId(),
                    projection.snapshot().getAggregateVersion(),
                    projection.integrityDiscriminator());
        }
        if (resolutionResult == WriteResult.STALE) {
            return;
        }

        WriteResult publicationResult = writeActive(
                expected.drafts().get(EvidenceSourceType.CASE_EVIDENCE),
                embeddings.get(expected.positions().get(EvidenceSourceType.CASE_EVIDENCE)),
                projection.integrityDiscriminator(),
                true,
                expected.resolutionExpected());
        if (publicationResult == WriteResult.STALE) {
            return;
        }
        if (assess(
                        projection.snapshot().getCaseId(),
                        projection.snapshot().getAggregateVersion(),
                        projection.integrityDiscriminator(),
                        expected.resolutionExpected())
                != ProjectionAssessment.NO_OP) {
            throw new EvidenceStoreUnavailableException("OpenSearch evidence publication remained incomplete");
        }
    }

    private ProjectionAssessment assess(
            String caseId, long version, String integrity, boolean resolutionExpected) {
        Current evidence = read(evidenceId(caseId));
        Current resolution = read(resolutionId(caseId));
        if (isNewer(evidence, version) || isNewer(resolution, version)) {
            return ProjectionAssessment.NO_OP;
        }
        requireCompatibleAtSameVersion(evidence, version, integrity);
        requireCompatibleAtSameVersion(resolution, version, integrity);

        boolean evidenceComplete = isCompleteActive(
                evidence,
                evidenceId(caseId),
                EvidenceSourceType.CASE_EVIDENCE,
                caseId,
                version,
                integrity,
                true,
                resolutionExpected);
        boolean resolutionComplete = resolutionExpected
                ? isCompleteActive(
                        resolution,
                        resolutionId(caseId),
                        EvidenceSourceType.RESOLUTION,
                        caseId,
                        version,
                        integrity,
                        null,
                        null)
                : isCompleteAbsent(resolution, resolutionId(caseId), version, integrity);
        return evidenceComplete && resolutionComplete
                ? ProjectionAssessment.NO_OP
                : ProjectionAssessment.APPLY;
    }

    private WriteResult writeActive(
            EvidenceDraft draft,
            float[] embedding,
            String integrity,
            Boolean publicationComplete,
            Boolean resolutionExpected) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sourceId", draft.sourceId());
        document.put("sourceType", draft.sourceType().name());
        document.put("caseId", draft.caseId());
        document.put("text", draft.text());
        document.put("embedding", embedding);
        document.put("projectionVersion", draft.projectionVersion());
        document.put("projectionIntegrity", integrity);
        document.put("chunkState", ACTIVE);
        if (publicationComplete != null) {
            document.put("publicationComplete", publicationComplete);
        }
        if (resolutionExpected != null) {
            document.put("resolutionExpected", resolutionExpected);
        }
        return writeDocument(
                draft.sourceId(), draft.projectionVersion(), integrity, ACTIVE,
                draft.sourceType(), draft.caseId(), publicationComplete, resolutionExpected, document);
    }

    private WriteResult writeAbsentResolution(String caseId, long version, String integrity) {
        String sourceId = resolutionId(caseId);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sourceId", sourceId);
        document.put("projectionVersion", version);
        document.put("projectionIntegrity", integrity);
        document.put("chunkState", ABSENT);
        return writeDocument(sourceId, version, integrity, ABSENT, null, null, null, null, document);
    }

    private WriteResult writeDocument(
            String sourceId,
            long version,
            String integrity,
            String targetState,
            EvidenceSourceType sourceType,
            String caseId,
            Boolean publicationComplete,
            Boolean resolutionExpected,
            Map<String, Object> document) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Current current = read(sourceId);
            if (isNewer(current, version)) {
                return WriteResult.STALE;
            }
            requireCompatibleAtSameVersion(current, version, integrity);
            if (matchesTarget(
                    current,
                    sourceId,
                    sourceType,
                    caseId,
                    version,
                    integrity,
                    targetState,
                    publicationComplete,
                    resolutionExpected)) {
                return WriteResult.PRESENT;
            }

            String uri = current == null
                    ? "/{alias}/_doc/{id}?op_type=create&refresh=wait_for"
                    : "/{alias}/_doc/{id}?if_seq_no=" + current.sequence()
                            + "&if_primary_term=" + current.primaryTerm() + "&refresh=wait_for";
            try {
                client.put().uri(uri, properties.writeAlias(), sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(LogSafeJsonBody.of(mapper, document))
                        .retrieve()
                        .toBodilessEntity();
                return WriteResult.WRITTEN;
            } catch (HttpClientErrorException.Conflict conflict) {
                // Another writer raced this CAS. Re-read and apply the full rules again.
            } catch (RuntimeException error) {
                throw new EvidenceStoreUnavailableException("OpenSearch evidence write failed");
            }
        }
        throw new EvidenceStoreUnavailableException(
                "OpenSearch evidence write remained concurrent after " + MAX_ATTEMPTS + " attempts");
    }

    @SuppressWarnings("unchecked")
    private Current read(String sourceId) {
        try {
            String json = client.get().uri("/{alias}/_doc/{id}", properties.readAlias(), sourceId)
                    .retrieve().body(String.class);
            Map<String, Object> value = mapper.readValue(json, Map.class);
            Map<String, Object> source = (Map<String, Object>) value.get("_source");
            long version = ((Number) source.get("projectionVersion")).longValue();
            long sequence = ((Number) value.get("_seq_no")).longValue();
            long primaryTerm = ((Number) value.get("_primary_term")).longValue();
            String state = source.get("chunkState") instanceof String valueState ? valueState : ACTIVE;
            String integrity = source.get("projectionIntegrity") instanceof String valueIntegrity
                    ? valueIntegrity : null;
            return new Current(
                    version,
                    integrity,
                    state,
                    source.get("sourceId") instanceof String valueSourceId ? valueSourceId : null,
                    source.get("sourceType") instanceof String valueSourceType ? valueSourceType : null,
                    source.get("caseId") instanceof String valueCaseId ? valueCaseId : null,
                    source.get("text") instanceof String,
                    source.get("embedding") instanceof List<?>,
                    source.get("publicationComplete") instanceof Boolean valuePublicationComplete
                            ? valuePublicationComplete : null,
                    source.get("resolutionExpected") instanceof Boolean valueResolutionExpected
                            ? valueResolutionExpected : null,
                    sequence,
                    primaryTerm);
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (RuntimeException error) {
            throw new EvidenceStoreUnavailableException("OpenSearch evidence read failed");
        }
    }

    private static ExpectedChunks expectedChunks(
            ValidatedProjection projection, List<EvidenceDraft> drafts) {
        String caseId = projection.snapshot().getCaseId();
        long version = projection.snapshot().getAggregateVersion();
        Map<EvidenceSourceType, EvidenceDraft> byType = new EnumMap<>(EvidenceSourceType.class);
        Map<EvidenceSourceType, Integer> positions = new EnumMap<>(EvidenceSourceType.class);
        for (int index = 0; index < drafts.size(); index++) {
            EvidenceDraft draft = drafts.get(index);
            if (!caseId.equals(draft.caseId()) || version != draft.projectionVersion()
                    || !draft.sourceId().equals(sourceId(caseId, draft.sourceType()))
                    || byType.put(draft.sourceType(), draft) != null) {
                throw new IllegalArgumentException("Evidence drafts do not match the validated projection");
            }
            positions.put(draft.sourceType(), index);
        }
        boolean resolutionExpected = "RESOLVED".equals(projection.snapshot().getStatus());
        if (!byType.containsKey(EvidenceSourceType.CASE_EVIDENCE)
                || byType.containsKey(EvidenceSourceType.RESOLUTION) != resolutionExpected
                || byType.size() != (resolutionExpected ? 2 : 1)) {
            throw new IllegalArgumentException("Evidence drafts do not contain the expected lifecycle chunk set");
        }
        return new ExpectedChunks(Map.copyOf(byType), Map.copyOf(positions), resolutionExpected);
    }

    private static boolean isNewer(Current current, long incomingVersion) {
        return current != null && current.projectionVersion() > incomingVersion;
    }

    private static void requireCompatibleAtSameVersion(
            Current current, long incomingVersion, String incomingIntegrity) {
        if (current != null && current.projectionVersion() == incomingVersion
                && !incomingIntegrity.equals(current.integrity())) {
            throw new ProjectionIntegrityException(
                    "Same aggregate version has a different projection integrity discriminator");
        }
    }

    private static boolean isCompleteActive(
            Current current,
            String sourceId,
            EvidenceSourceType sourceType,
            String caseId,
            long version,
            String integrity,
            Boolean publicationComplete,
            Boolean resolutionExpected) {
        return matchesTarget(
                current,
                sourceId,
                sourceType,
                caseId,
                version,
                integrity,
                ACTIVE,
                publicationComplete,
                resolutionExpected);
    }

    private static boolean isCompleteAbsent(
            Current current, String sourceId, long version, String integrity) {
        return matchesTarget(current, sourceId, null, null, version, integrity, ABSENT, null, null);
    }

    private static boolean matchesTarget(
            Current current,
            String sourceId,
            EvidenceSourceType sourceType,
            String caseId,
            long version,
            String integrity,
            String targetState,
            Boolean publicationComplete,
            Boolean resolutionExpected) {
        if (current == null || current.projectionVersion() != version
                || !integrity.equals(current.integrity()) || !targetState.equals(current.state())
                || !sourceId.equals(current.sourceId())) {
            return false;
        }
        if (ACTIVE.equals(targetState)) {
            return sourceType.name().equals(current.sourceType())
                    && caseId.equals(current.caseId())
                    && current.hasText()
                    && current.hasEmbedding()
                    && java.util.Objects.equals(publicationComplete, current.publicationComplete())
                    && java.util.Objects.equals(resolutionExpected, current.resolutionExpected());
        }
        return current.sourceType() == null && current.caseId() == null
                && !current.hasText() && !current.hasEmbedding()
                && current.publicationComplete() == null && current.resolutionExpected() == null;
    }

    private static String sourceId(String caseId, EvidenceSourceType sourceType) {
        return switch (sourceType) {
            case CASE_EVIDENCE -> evidenceId(caseId);
            case RESOLUTION -> resolutionId(caseId);
        };
    }

    private static String evidenceId(String caseId) {
        return "case:" + caseId + ":evidence";
    }

    private static String resolutionId(String caseId) {
        return "case:" + caseId + ":resolution";
    }

    private static String legacyIntegrity(EvidenceDraft draft) {
        String canonical = draft.sourceId() + '\u0000' + draft.sourceType().name() + '\u0000'
                + draft.caseId() + '\u0000' + draft.text() + '\u0000' + draft.projectionVersion();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum WriteResult {
        WRITTEN,
        PRESENT,
        STALE
    }

    private record ExpectedChunks(
            Map<EvidenceSourceType, EvidenceDraft> drafts,
            Map<EvidenceSourceType, Integer> positions,
            boolean resolutionExpected) {
    }

    private record Current(
            long projectionVersion,
            String integrity,
            String state,
            String sourceId,
            String sourceType,
            String caseId,
            boolean hasText,
            boolean hasEmbedding,
            Boolean publicationComplete,
            Boolean resolutionExpected,
            long sequence,
            long primaryTerm) {
        @Override
        public String toString() {
            return "Current[sourceId=" + sourceId + ", content=<redacted>]";
        }
    }
}
