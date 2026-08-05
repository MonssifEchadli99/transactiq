package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SafeEvidenceMapperTest {

    private final SafeEvidenceMapper mapper = new SafeEvidenceMapper();

    @Test
    void unresolvedCaseProducesExactlyOneEvidenceChunk() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionSnapshot snapshot =
                ProjectionFixtures.snapshotOf(ProjectionFixtures.createdEvent(caseId, 0));

        List<EvidenceDraft> drafts = mapper.map(snapshot);

        assertEquals(1, drafts.size());
        assertEquals(EvidenceSourceType.CASE_EVIDENCE, drafts.get(0).sourceType());
        assertEquals("case:" + caseId + ":evidence", drafts.get(0).sourceId());
    }

    @Test
    void resolvedCaseProducesEvidenceAndResolutionChunks() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionSnapshot snapshot = ProjectionFixtures.snapshotOf(
                ProjectionFixtures.resolvedEvent(caseId, 2, "CONFIRMED_FRAUD", "synthetic confirmed pattern"));

        List<EvidenceDraft> drafts = mapper.map(snapshot);

        assertEquals(2, drafts.size());
        assertEquals(EvidenceSourceType.CASE_EVIDENCE, drafts.get(0).sourceType());
        assertEquals(EvidenceSourceType.RESOLUTION, drafts.get(1).sourceType());
        assertEquals("case:" + caseId + ":evidence", drafts.get(0).sourceId());
        assertEquals("case:" + caseId + ":resolution", drafts.get(1).sourceId());
        assertTrue(drafts.get(1).text().contains("synthetic confirmed pattern"));
        assertTrue(drafts.get(1).text().contains("CONFIRMED_FRAUD"));
    }

    @Test
    void malformedUnresolvedSnapshotCannotCreateAResolutionChunk() {
        FraudCaseProjectionSnapshot malformed = ProjectionFixtures.snapshotOf(
                        ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0))
                .toBuilder()
                .setResolutionOutcome("FALSE_POSITIVE")
                .setResolutionRationale("synthetic metadata that is invalid while unresolved")
                .build();

        List<EvidenceDraft> drafts = mapper.map(malformed);

        assertEquals(1, drafts.size());
        assertEquals(EvidenceSourceType.CASE_EVIDENCE, drafts.getFirst().sourceType());
    }

    @Test
    void completeProhibitedValueMatrixNeverReachesCanonicalEvidence() {
        String caseId = UUID.randomUUID().toString();
        Map<String, String> prohibited = Map.ofEntries(
                Map.entry("authorization request ID", "CANARY_REQUEST_ID_DO_NOT_EMBED"),
                Map.entry("card token", "CANARY_SYNTHETIC_CARD_TOKEN_DO_NOT_EMBED"),
                Map.entry("card fingerprint", "CANARY_SYNTHETIC_CARD_FINGERPRINT_DO_NOT_EMBED"),
                Map.entry("account identifier", "CANARY_SYNTHETIC_ACCOUNT_ID_DO_NOT_EMBED"),
                Map.entry("assignee identity", "CANARY_ASSIGNEE_ID_DO_NOT_EMBED"),
                Map.entry("resolution actor identity", "CANARY_RESOLVED_BY_DO_NOT_EMBED"),
                Map.entry("projection event ID", "CANARY_PROJECTION_EVENT_ID_DO_NOT_EMBED"),
                Map.entry("snapshot hash", "CANARY_SNAPSHOT_HASH_DO_NOT_EMBED"),
                Map.entry("payload hash", "CANARY_PAYLOAD_HASH_DO_NOT_EMBED"),
                Map.entry("Kafka topic", "CANARY_KAFKA_TOPIC_DO_NOT_EMBED"),
                Map.entry("Kafka partition", "CANARY_KAFKA_PARTITION_DO_NOT_EMBED"),
                Map.entry("Kafka offset", "CANARY_KAFKA_OFFSET_DO_NOT_EMBED"),
                Map.entry("infrastructure detail", "CANARY_OPENSEARCH_ENDPOINT_DO_NOT_EMBED"),
                Map.entry("credential", "CANARY_OBVIOUSLY_FAKE_TEST_CREDENTIAL_DO_NOT_EMBED"),
                Map.entry("vector", "CANARY_EMBEDDING_VECTOR_DO_NOT_EMBED"),
                Map.entry("raw score", "CANARY_RAW_RETRIEVAL_SCORE_DO_NOT_EMBED"));
        FraudCaseProjectionEvent base = ProjectionFixtures.resolvedEvent(
                caseId, 1, "FALSE_POSITIVE", "synthetic rationale text");
        FraudCaseProjectionSnapshot snapshot = base.getSnapshot().toBuilder()
                .setRequestId(prohibited.get("authorization request ID"))
                .setAssigneeId(prohibited.get("assignee identity"))
                .setResolvedBy(prohibited.get("resolution actor identity"))
                .setUnknownFields(unknownFields(
                        100,
                        prohibited.get("card token"),
                        prohibited.get("card fingerprint"),
                        prohibited.get("account identifier"),
                        prohibited.get("projection event ID"),
                        prohibited.get("snapshot hash"),
                        prohibited.get("payload hash"),
                        prohibited.get("Kafka topic"),
                        prohibited.get("Kafka partition"),
                        prohibited.get("Kafka offset"),
                        prohibited.get("infrastructure detail"),
                        prohibited.get("credential"),
                        prohibited.get("vector"),
                        prohibited.get("raw score")))
                .build();
        FraudCaseProjectionEvent originalProjection = base.toBuilder()
                .setEventId(prohibited.get("projection event ID"))
                .setSnapshotHash(prohibited.get("snapshot hash"))
                .setSnapshot(snapshot)
                .build();

        List<EvidenceDraft> drafts = mapper.map(originalProjection.getSnapshot());
        String allText = drafts.get(0).text() + "\n" + drafts.get(1).text();

        assertTrue(allText.contains(caseId), "focal case id must appear as an approved field");
        assertTrue(allText.contains("merchant-review"));
        assertTrue(allText.contains("VELOCITY"));
        assertTrue(allText.contains("HIGH_RISK"));

        prohibited.forEach((field, sentinel) ->
                assertFalse(allText.contains(sentinel), () -> field + " must never reach canonical evidence"));
        assertFalse(allText.toLowerCase(java.util.Locale.ROOT).contains("aggregateversion"),
                "projection version must never be labelled or exposed in embedding text");
    }

    @Test
    void unknownNestedRuleFieldsAndUnknownProtobufFieldsAreOmitted() throws Exception {
        String unknownKey = "CANARY_UNKNOWN_NESTED_RULE_KEY_DO_NOT_EMBED";
        String unknownValue = "CANARY_UNKNOWN_NESTED_RULE_VALUE_DO_NOT_EMBED";
        FraudCaseProjectionSnapshot base = ProjectionFixtures.snapshotOf(
                ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0));
        FraudRuleEvidence ruleWithUnknownFields = base.getMatchedRules(0).toBuilder()
                .setUnknownFields(unknownFields(140, unknownKey, unknownValue))
                .build();
        FraudCaseProjectionSnapshot withUnknownFields = base.toBuilder()
                .setMatchedRules(0, ruleWithUnknownFields)
                .setUnknownFields(unknownFields(160, "CANARY_UNKNOWN_SNAPSHOT_FIELD_DO_NOT_EMBED"))
                .build();
        FraudCaseProjectionSnapshot reparsed =
                FraudCaseProjectionSnapshot.parseFrom(withUnknownFields.toByteArray());

        assertTrue(reparsed.getMatchedRules(0).getUnknownFields().hasField(140));
        assertTrue(reparsed.getUnknownFields().hasField(160));
        String canonicalText = mapper.map(reparsed).getFirst().text();
        assertFalse(canonicalText.contains(unknownKey));
        assertFalse(canonicalText.contains(unknownValue));
        assertFalse(canonicalText.contains("CANARY_UNKNOWN_SNAPSHOT_FIELD_DO_NOT_EMBED"));
    }

    @Test
    void reversingOnlyTheMatchedRuleInputListKeepsCanonicalOutputIdentical() {
        FraudCaseProjectionSnapshot original = ProjectionFixtures.snapshotOf(
                ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0));
        List<FraudRuleEvidence> reversedRules = new ArrayList<>(original.getMatchedRulesList());
        Collections.reverse(reversedRules);
        FraudCaseProjectionSnapshot reversed = original.toBuilder()
                .clearMatchedRules()
                .addAllMatchedRules(reversedRules)
                .build();

        assertEquals(original.toBuilder().clearMatchedRules().build(),
                reversed.toBuilder().clearMatchedRules().build(),
                "every field except matched-rule source order must remain identical");
        assertEquals(mapper.map(original), mapper.map(reversed));
    }

    @Test
    void mappingTheSameSnapshotTwiceProducesIdenticalDeterministicOutput() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionSnapshot snapshot =
                ProjectionFixtures.snapshotOf(ProjectionFixtures.createdEvent(caseId, 5));

        List<EvidenceDraft> first = mapper.map(snapshot);
        List<EvidenceDraft> second = mapper.map(snapshot);

        assertEquals(first, second);
    }

    private static UnknownFieldSet unknownFields(int firstFieldNumber, String... values) {
        UnknownFieldSet.Builder unknown = UnknownFieldSet.newBuilder();
        for (int index = 0; index < values.length; index++) {
            unknown.addField(
                    firstFieldNumber + index,
                    UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFromUtf8(values[index]))
                            .build());
        }
        return unknown.build();
    }
}
