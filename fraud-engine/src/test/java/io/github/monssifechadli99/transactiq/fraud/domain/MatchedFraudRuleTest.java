package io.github.monssifechadli99.transactiq.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MatchedFraudRuleTest {

    @Test
    void createsValidMatchedRule() {
        MatchedFraudRule rule = new MatchedFraudRule(
                "RISKY_MCC",
                FraudRuleSeverity.REVIEW,
                "MCC 7995 has synthetic REVIEW classification");

        assertEquals("RISKY_MCC", rule.ruleCode());
        assertEquals(FraudRuleSeverity.REVIEW, rule.severity());
        assertEquals("MCC 7995 has synthetic REVIEW classification", rule.evidence());
    }

    @Test
    void rejectsBlankRuleCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MatchedFraudRule(" ", FraudRuleSeverity.REVIEW, "evidence"));
    }

    @Test
    void rejectsNullRuleCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MatchedFraudRule(null, FraudRuleSeverity.REVIEW, "evidence"));
    }

    @Test
    void rejectsNullSeverity() {
        assertThrows(
                NullPointerException.class,
                () -> new MatchedFraudRule("RULE_CODE", null, "evidence"));
    }

    @Test
    void rejectsBlankEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MatchedFraudRule("RULE_CODE", FraudRuleSeverity.HIGH_RISK, " "));
    }
}
