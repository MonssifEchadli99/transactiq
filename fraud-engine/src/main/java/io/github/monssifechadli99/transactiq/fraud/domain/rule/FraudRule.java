package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.util.Optional;

public interface FraudRule {

    Optional<MatchedFraudRule> evaluate(FraudRuleContext context);
}
