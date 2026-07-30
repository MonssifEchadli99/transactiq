package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Claimed;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Completed;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc.FraudGrpcClientAdapter;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.support.PostgreSqlTestcontainersConfiguration;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentOutcome;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatch;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatchSeverity;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "authorization.outbox.publisher.enabled=false")
@Import(PostgreSqlTestcontainersConfiguration.class)
class FraudGrpcAuthorizationHttpIntegrationTest {

    private static final FakeFraudTcpServer FRAUD_SERVER = FakeFraudTcpServer.start();
    private static final Instant TRANSACTION_TIME =
            Instant.parse("2026-07-21T10:15:30.123456789Z");
    private static final UUID FUNDED_ACCOUNT_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000099");
    private static final String FUNDED_CARD_TOKEN = "tok_GRPC0001";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Set<UUID> requestIds = ConcurrentHashMap.newKeySet();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private IdempotencyClaimPort idempotencyClaimPort;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void fraudGrpcProperties(DynamicPropertyRegistry registry) {
        registry.add("fraud.grpc.client.host", () -> "localhost");
        registry.add("fraud.grpc.client.port", FRAUD_SERVER::port);
        registry.add("fraud.grpc.client.deadline", () -> "1s");
        registry.add("fraud.grpc.client.plaintext", () -> "true");
    }

    @BeforeEach
    void resetState() {
        removeTestData();
        FRAUD_SERVER.reset();
        createFundedTestAccount();
        jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET reserved_amount = 0.00
                        WHERE card_token = 'tok_insufficient01'
                        """)
                .update();
    }

    @AfterEach
    void removeState() {
        FRAUD_SERVER.releaseBlockedCall();
        removeTestData();
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".card_accounts
                        WHERE account_id = :accountId
                        """)
                .param("accountId", FUNDED_ACCOUNT_ID)
                .update();
    }

    @AfterAll
    static void stopFraudServer() throws Exception {
        FRAUD_SERVER.close();
    }

    @Test
    void productionContextHasOnlyTheGrpcFraudPortImplementation() {
        var fraudPorts = applicationContext.getBeansOfType(FraudAssessmentPort.class);

        assertEquals(1, fraudPorts.size());
        assertInstanceOf(
                FraudGrpcClientAdapter.class, fraudPorts.values().iterator().next());
    }

    @Test
    void clearReviewAndHighRiskDriveApprovedMappingWithoutExposingEvidence() throws Exception {
        AuthorizationCommand clear = command(
                "81000000-0000-4000-8000-000000000001",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        AuthorizationCommand review = command(
                "81000000-0000-4000-8000-000000000002",
                "tok_A1B2C3D4",
                "merchant-review",
                new BigDecimal("20.00"));
        AuthorizationCommand highRisk = command(
                "81000000-0000-4000-8000-000000000003",
                "tok_A1B2C3D4",
                "merchant-high",
                new BigDecimal("30.00"));

        FRAUD_SERVER.respondWith(clearResponse());
        HttpResponse<String> clearResponse = post(clear);
        FRAUD_SERVER.respondWith(reviewResponse());
        HttpResponse<String> reviewResponse = post(review);
        FRAUD_SERVER.respondWith(highRiskResponse());
        HttpResponse<String> highRiskResponse = post(highRisk);

        assertBusinessResponse(clearResponse, 200, "APPROVED", null);
        assertBusinessResponse(reviewResponse, 200, "APPROVED", null);
        assertBusinessResponse(highRiskResponse, 200, "DECLINED", "HIGH_FRAUD_RISK");
        assertEquals(1, FRAUD_SERVER.invocationCount(clear.requestId()));
        assertEquals(1, FRAUD_SERVER.invocationCount(review.requestId()));
        assertEquals(1, FRAUD_SERVER.invocationCount(highRisk.requestId()));
        assertEquals("CLEAR", storedAssessment(clear.requestId()));
        assertEquals("REVIEW", storedAssessment(review.requestId()));
        assertEquals("HIGH_RISK", storedAssessment(highRisk.requestId()));
        assertEquals(0, storedScore(clear.requestId()));
        assertEquals(15, storedScore(review.requestId()));
        assertEquals(85, storedScore(highRisk.requestId()));
        assertEquals(
                List.of(new StoredMatch(
                        "AMOUNT_REVIEW", "REVIEW", "Synthetic review evidence", 15)),
                storedMatches(review.requestId()));
        assertEquals(
                List.of(
                        new StoredMatch(
                                "COUNTRY_REVIEW", "REVIEW", "Synthetic country evidence", 15),
                        new StoredMatch(
                                "MCC_HIGH", "HIGH_RISK", "Synthetic high-risk evidence", 70)),
                storedMatches(highRisk.requestId()));
    }

    @Test
    void highRiskStillRunsFundsCheckAndInsufficientFundsKeepsPrecedence() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000004",
                "tok_insufficient01",
                "merchant-high",
                new BigDecimal("42.50"));
        FRAUD_SERVER.respondWith(highRiskResponse());

        HttpResponse<String> response = post(command);

        assertBusinessResponse(response, 200, "DECLINED", "INSUFFICIENT_FUNDS");
        assertEquals(1, FRAUD_SERVER.invocationCount(command.requestId()));
        assertEquals(command.requestId().toString(),
                FRAUD_SERVER.requests().getFirst().getRequestId());
        assertEquals(1, ledgerCount(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));
    }

    @Test
    void validationCompletedPendingAndConflictShortCircuitFraudAsOrdered() throws Exception {
        AuthorizationCommand invalid = command(
                "81000000-0000-4000-8000-000000000005",
                "invalid-token",
                "merchant-clear",
                new BigDecimal("10.00"));
        HttpResponse<String> invalidResponse = post(invalid);
        assertEquals(400, invalidResponse.statusCode());
        assertEquals(0, FRAUD_SERVER.invocationCount(invalid.requestId()));
        assertEquals(0, outboxCount(invalid.requestId()));

        AuthorizationCommand completed = command(
                "81000000-0000-4000-8000-000000000006",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.respondWith(clearResponse());
        HttpResponse<String> first = post(completed);
        HttpResponse<String> retry = post(completed);
        assertEquals(first.body(), retry.body());
        assertEquals(1, FRAUD_SERVER.invocationCount(completed.requestId()));
        assertEquals(1, outboxCount(completed.requestId()));

        AuthorizationCommand pending = command(
                "81000000-0000-4000-8000-000000000007",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        assertInstanceOf(Claimed.class, idempotencyClaimPort.claim(pending));
        HttpResponse<String> pendingResponse = post(pending);
        assertEquals(202, pendingResponse.statusCode());
        assertEquals(0, FRAUD_SERVER.invocationCount(pending.requestId()));
        assertEquals(0, outboxCount(pending.requestId()));

        AuthorizationCommand original = command(
                "81000000-0000-4000-8000-000000000008",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        assertInstanceOf(Claimed.class, idempotencyClaimPort.claim(original));
        AuthorizationCommand conflicting = new AuthorizationCommand(
                original.requestId(),
                original.cardToken(),
                original.merchantId(),
                original.merchantCategoryCode(),
                new BigDecimal("11.00"),
                original.currency(),
                original.country(),
                original.channel(),
                original.transactionTime());
        HttpResponse<String> conflictResponse = post(conflicting);
        assertEquals(409, conflictResponse.statusCode());
        assertEquals("{\"code\":\"REQUEST_ID_CONFLICT\"}", conflictResponse.body());
        assertEquals(0, FRAUD_SERVER.invocationCount(original.requestId()));
        assertEquals(0, outboxCount(original.requestId()));
    }

    @Test
    void concurrentIdenticalRequestsProduceOneGrpcCall() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000009",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("15.00"));
        FRAUD_SERVER.blockWith(clearResponse());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<HttpResponse<String>> first = executor.submit(() -> post(command));
            assertTrue(FRAUD_SERVER.awaitBlockedCall(10, TimeUnit.SECONDS));

            HttpResponse<String> concurrentRetry = post(command);
            assertEquals(202, concurrentRetry.statusCode());
            assertEquals(1, FRAUD_SERVER.invocationCount(command.requestId()));

            FRAUD_SERVER.releaseBlockedCall();
            HttpResponse<String> completed = first.get(10, TimeUnit.SECONDS);
            assertBusinessResponse(completed, 200, "APPROVED", null);
        }
        assertEquals(1, FRAUD_SERVER.invocationCount(command.requestId()));
    }

    @Test
    void deadlineAndUnavailableReleaseClaimAndAllowSuccessfulRetry() throws Exception {
        AuthorizationCommand deadline = command(
                "81000000-0000-4000-8000-000000000010",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.failFirstThen(null, clearResponse());

        assertTechnicalFailureWithoutEffects(post(deadline), deadline.requestId());
        HttpResponse<String> deadlineRetry = post(deadline);
        assertBusinessResponse(deadlineRetry, 200, "APPROVED", null);
        assertEquals(2, FRAUD_SERVER.invocationCount(deadline.requestId()));

        AuthorizationCommand unavailable = command(
                "81000000-0000-4000-8000-000000000011",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.failFirstThen(Status.UNAVAILABLE, clearResponse());

        assertTechnicalFailureWithoutEffects(post(unavailable), unavailable.requestId());
        HttpResponse<String> unavailableRetry = post(unavailable);
        assertBusinessResponse(unavailableRetry, 200, "APPROVED", null);
        assertEquals(2, FRAUD_SERVER.invocationCount(unavailable.requestId()));
    }

    @Test
    void failedPreconditionReturnsConflictReleasesClaimAndAllowsRetry() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000012",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.failFirstThen(Status.FAILED_PRECONDITION, clearResponse());

        HttpResponse<String> conflict = post(command);

        assertEquals(409, conflict.statusCode());
        assertEquals("{\"code\":\"REQUEST_ID_CONFLICT\"}", conflict.body());
        assertEquals(0, requestCount(command.requestId()));
        assertEquals(0, ledgerCount(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));

        HttpResponse<String> retry = post(command);
        assertBusinessResponse(retry, 200, "APPROVED", null);
        assertEquals(2, FRAUD_SERVER.invocationCount(command.requestId()));
    }

    @Test
    void invalidArgumentAfterHttpValidationIsTechnicalAndReleasesClaim() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000014",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.failFirstThen(Status.INVALID_ARGUMENT, clearResponse());

        assertTechnicalFailureWithoutEffects(post(command), command.requestId());

        HttpResponse<String> retry = post(command);
        assertBusinessResponse(retry, 200, "APPROVED", null);
        assertEquals(2, FRAUD_SERVER.invocationCount(command.requestId()));
    }

    @Test
    void malformedScoreIsGenericTechnicalFailureAndLeavesNoPersistence() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000015",
                "tok_A1B2C3D4",
                "merchant-clear",
                new BigDecimal("10.00"));
        FRAUD_SERVER.respondWith(AssessFraudResponse.newBuilder()
                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR)
                .build());

        assertTechnicalFailureWithoutEffects(post(command), command.requestId());
        assertEquals(1, FRAUD_SERVER.invocationCount(command.requestId()));
    }

    @Test
    void completedRetryUsesStoredOriginalAssessmentAndMatches() throws Exception {
        AuthorizationCommand command = command(
                "81000000-0000-4000-8000-000000000013",
                "tok_A1B2C3D4",
                "merchant-review",
                new BigDecimal("10.00"));
        FRAUD_SERVER.respondWith(reviewResponse());
        HttpResponse<String> first = post(command);
        Completed storedResult = assertInstanceOf(
                Completed.class, idempotencyClaimPort.claim(command));
        assertEquals(FraudAssessment.REVIEW, storedResult.fraudAssessment().assessment());
        assertEquals(15, storedResult.fraudAssessment().riskScore());
        assertEquals(
                List.of(new FraudRuleMatch(
                        "AMOUNT_REVIEW",
                        FraudRuleSeverity.REVIEW,
                        "Synthetic review evidence",
                        15)),
                storedResult.fraudAssessment().matchedRules());
        FRAUD_SERVER.respondWith(highRiskResponse());

        HttpResponse<String> retry = post(command);

        assertEquals(first.body(), retry.body());
        assertBusinessResponse(retry, 200, "APPROVED", null);
        assertEquals(1, FRAUD_SERVER.invocationCount(command.requestId()));
        assertEquals("REVIEW", storedAssessment(command.requestId()));
        assertEquals(15, storedScore(command.requestId()));
        assertEquals(
                List.of(new StoredMatch(
                        "AMOUNT_REVIEW", "REVIEW", "Synthetic review evidence", 15)),
                storedMatches(command.requestId()));
    }

    private void assertTechnicalFailureWithoutEffects(
            HttpResponse<String> response, UUID requestId) {
        assertEquals(500, response.statusCode());
        assertEquals("{\"code\":\"AUTHORIZATION_PROCESSING_ERROR\"}", response.body());
        assertEquals(0, requestCount(requestId));
        assertEquals(0, ledgerCount(requestId));
        assertEquals(0, fraudMatchCount(requestId));
        assertEquals(0, reservationCount(requestId));
        assertEquals(0, outboxCount(requestId));
    }

    private static void assertBusinessResponse(
            HttpResponse<String> response,
            int expectedStatus,
            String decision,
            String declineReason) {
        assertEquals(expectedStatus, response.statusCode());
        assertTrue(response.body().contains("\"decision\":\"" + decision + "\""), response.body());
        if (declineReason == null) {
            assertTrue(!response.body().contains("declineReason"), response.body());
        } else {
            assertTrue(
                    response.body().contains("\"declineReason\":\"" + declineReason + "\""),
                    response.body());
        }
        assertTrue(!response.body().contains("evidence"), response.body());
        assertTrue(!response.body().contains("matchedRules"), response.body());
        assertTrue(!response.body().contains("riskScore"), response.body());
        assertTrue(!response.body().contains("scoreContribution"), response.body());
    }

    private HttpResponse<String> post(AuthorizationCommand command) throws Exception {
        requestIds.add(command.requestId());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/authorizations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(command)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void removeTestData() {
        if (requestIds.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(requestIds);
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".balance_reservations
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", ids)
                .update();
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_ledger
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", ids)
                .update();
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_requests
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", ids)
                .update();
        requestIds.clear();
    }

    private void createFundedTestAccount() {
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".card_accounts (
                            account_id,
                            card_token,
                            currency,
                            posted_balance,
                            reserved_amount
                        ) VALUES (
                            :accountId,
                            :cardToken,
                            'EUR',
                            1000.00,
                            0.00
                        )
                        ON CONFLICT (account_id) DO UPDATE
                        SET reserved_amount = 0.00
                        """)
                .param("accountId", FUNDED_ACCOUNT_ID)
                .param("cardToken", FUNDED_CARD_TOKEN)
                .update();
    }

    private String storedAssessment(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT fraud_assessment
                        FROM "authorization".authorization_ledger
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .single();
    }

    private int storedScore(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT risk_score
                        FROM "authorization".authorization_ledger
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    private List<StoredMatch> storedMatches(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT rule_code, severity, evidence, score_contribution
                        FROM "authorization".fraud_rule_matches
                        WHERE request_id = :requestId
                        ORDER BY match_order
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new StoredMatch(
                        resultSet.getString("rule_code"),
                        resultSet.getString("severity"),
                        resultSet.getString("evidence"),
                        resultSet.getInt("score_contribution")))
                .list();
    }

    private int requestCount(UUID requestId) {
        return count("authorization_requests", requestId);
    }

    private int ledgerCount(UUID requestId) {
        return count("authorization_ledger", requestId);
    }

    private int reservationCount(UUID requestId) {
        return count("balance_reservations", requestId);
    }

    private int fraudMatchCount(UUID requestId) {
        return count("fraud_rule_matches", requestId);
    }

    private int outboxCount(UUID requestId) {
        return count("authorization_outbox", requestId);
    }

    private int count(String table, UUID requestId) {
        String sql = switch (table) {
            case "authorization_requests" -> """
                    SELECT COUNT(*)
                    FROM "authorization".authorization_requests
                    WHERE request_id = :requestId
                    """;
            case "authorization_ledger" -> """
                    SELECT COUNT(*)
                    FROM "authorization".authorization_ledger
                    WHERE request_id = :requestId
                    """;
            case "balance_reservations" -> """
                    SELECT COUNT(*)
                    FROM "authorization".balance_reservations
                    WHERE request_id = :requestId
                    """;
            case "fraud_rule_matches" -> """
                    SELECT COUNT(*)
                    FROM "authorization".fraud_rule_matches
                    WHERE request_id = :requestId
                    """;
            case "authorization_outbox" -> """
                    SELECT COUNT(*)
                    FROM "authorization".authorization_outbox
                    WHERE request_id = :requestId
                    """;
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        return jdbcClient.sql(sql)
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    private static AuthorizationCommand command(
            String requestId, String cardToken, String merchantId, BigDecimal amount) {
        String effectiveCardToken = "tok_A1B2C3D4".equals(cardToken)
                ? FUNDED_CARD_TOKEN
                : cardToken;
        return new AuthorizationCommand(
                UUID.fromString(requestId),
                effectiveCardToken,
                merchantId,
                "5411",
                amount,
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private static String requestJson(AuthorizationCommand command) {
        return """
                {
                  "requestId": "%s",
                  "cardToken": "%s",
                  "merchantId": "%s",
                  "merchantCategoryCode": "%s",
                  "amount": %s,
                  "currency": "%s",
                  "country": "%s",
                  "channel": "%s",
                  "transactionTime": "%s"
                }
                """.formatted(
                command.requestId(),
                command.cardToken(),
                command.merchantId(),
                command.merchantCategoryCode(),
                command.amount().toPlainString(),
                command.currency(),
                command.country(),
                command.channel(),
                command.transactionTime());
    }

    private static AssessFraudResponse clearResponse() {
        return AssessFraudResponse.newBuilder()
                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR)
                .setRiskScore(0)
                .build();
    }

    private static AssessFraudResponse reviewResponse() {
        return AssessFraudResponse.newBuilder()
                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW)
                .setRiskScore(15)
                .addMatchedRules(rule(
                        "AMOUNT_REVIEW",
                        RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW,
                        "Synthetic review evidence",
                        15))
                .build();
    }

    private static AssessFraudResponse highRiskResponse() {
        return AssessFraudResponse.newBuilder()
                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK)
                .setRiskScore(85)
                .addMatchedRules(rule(
                        "COUNTRY_REVIEW",
                        RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW,
                        "Synthetic country evidence",
                        15))
                .addMatchedRules(rule(
                        "MCC_HIGH",
                        RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK,
                        "Synthetic high-risk evidence",
                        70))
                .build();
    }

    private static RuleMatch rule(
            String code,
            RuleMatchSeverity severity,
            String evidence,
            int scoreContribution) {
        return RuleMatch.newBuilder()
                .setRuleCode(code)
                .setSeverity(severity)
                .setEvidence(evidence)
                .setScoreContribution(scoreContribution)
                .build();
    }

    private record StoredMatch(
            String ruleCode, String severity, String evidence, int scoreContribution) {}

    private static final class FakeFraudTcpServer implements AutoCloseable {

        private final FakeFraudService service;
        private final Server server;

        private FakeFraudTcpServer(FakeFraudService service, Server server) {
            this.service = service;
            this.server = server;
        }

        private static FakeFraudTcpServer start() {
            try {
                FakeFraudService service = new FakeFraudService();
                Server server = ServerBuilder.forPort(0).addService(service).build().start();
                return new FakeFraudTcpServer(service, server);
            } catch (Exception failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private int port() {
            return server.getPort();
        }

        private void reset() {
            service.reset();
        }

        private void respondWith(AssessFraudResponse response) {
            service.respondWith(response);
        }

        private void failFirstThen(Status firstFailure, AssessFraudResponse response) {
            service.failFirstThen(firstFailure, response);
        }

        private void blockWith(AssessFraudResponse response) {
            service.blockWith(response);
        }

        private boolean awaitBlockedCall(long timeout, TimeUnit unit) throws InterruptedException {
            return service.awaitBlockedCall(timeout, unit);
        }

        private void releaseBlockedCall() {
            service.releaseBlockedCall();
        }

        private int invocationCount(UUID requestId) {
            return service.invocationCount(requestId);
        }

        private List<AssessFraudRequest> requests() {
            return service.requests();
        }

        @Override
        public void close() throws Exception {
            releaseBlockedCall();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class FakeFraudService
            extends FraudAssessmentServiceGrpc.FraudAssessmentServiceImplBase {

        private final List<AssessFraudRequest> requests =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<UUID, AtomicInteger> invocationCounts =
                new ConcurrentHashMap<>();

        private volatile Behavior behavior = success(clearResponse());
        private volatile CountDownLatch blockedCallEntered = new CountDownLatch(0);
        private volatile CountDownLatch blockedCallRelease = new CountDownLatch(0);

        @Override
        public void assess(
                AssessFraudRequest request,
                StreamObserver<AssessFraudResponse> responseObserver) {
            requests.add(request);
            invocationCounts
                    .computeIfAbsent(UUID.fromString(request.getRequestId()), ignored ->
                            new AtomicInteger())
                    .incrementAndGet();
            behavior.respond(responseObserver);
        }

        private void reset() {
            releaseBlockedCall();
            requests.clear();
            invocationCounts.clear();
            behavior = success(clearResponse());
            blockedCallEntered = new CountDownLatch(0);
            blockedCallRelease = new CountDownLatch(0);
        }

        private void respondWith(AssessFraudResponse response) {
            behavior = success(response);
        }

        private void failFirstThen(Status status, AssessFraudResponse response) {
            AtomicBoolean first = new AtomicBoolean(true);
            behavior = observer -> {
                if (first.compareAndSet(true, false)) {
                    if (status == null) {
                        return;
                    }
                    observer.onError(status.asRuntimeException());
                    return;
                }
                send(observer, response);
            };
        }

        private void blockWith(AssessFraudResponse response) {
            blockedCallEntered = new CountDownLatch(1);
            blockedCallRelease = new CountDownLatch(1);
            behavior = observer -> {
                blockedCallEntered.countDown();
                try {
                    if (!blockedCallRelease.await(10, TimeUnit.SECONDS)) {
                        observer.onError(Status.INTERNAL.asRuntimeException());
                        return;
                    }
                    send(observer, response);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    observer.onError(Status.CANCELLED.asRuntimeException());
                }
            };
        }

        private boolean awaitBlockedCall(long timeout, TimeUnit unit) throws InterruptedException {
            return blockedCallEntered.await(timeout, unit);
        }

        private void releaseBlockedCall() {
            blockedCallRelease.countDown();
        }

        private int invocationCount(UUID requestId) {
            AtomicInteger count = invocationCounts.get(requestId);
            return count == null ? 0 : count.get();
        }

        private List<AssessFraudRequest> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        private static Behavior success(AssessFraudResponse response) {
            return observer -> send(observer, response);
        }

        private static void send(
                StreamObserver<AssessFraudResponse> observer, AssessFraudResponse response) {
            observer.onNext(response);
            observer.onCompleted();
        }
    }

    @FunctionalInterface
    private interface Behavior {
        void respond(StreamObserver<AssessFraudResponse> responseObserver);
    }
}
