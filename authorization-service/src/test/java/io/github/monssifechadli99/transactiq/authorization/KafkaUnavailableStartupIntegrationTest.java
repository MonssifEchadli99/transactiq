package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.event.KafkaAuthorizationCompletedEventPublisher;
import io.github.monssifechadli99.transactiq.authorization.support.FraudAssessmentTestConfiguration;
import io.github.monssifechadli99.transactiq.authorization.support.PostgreSqlTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
    "fraud.grpc.client.enabled=false",
    "authorization.outbox.publisher.enabled=true",
    "spring.kafka.bootstrap-servers=127.0.0.1:1"
})
@Import({
    PostgreSqlTestcontainersConfiguration.class,
    FraudAssessmentTestConfiguration.class
})
class KafkaUnavailableStartupIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void unavailableKafkaDoesNotPreventApplicationStartup() {
        assertEquals(1, applicationContext
                .getBeansOfType(KafkaAuthorizationCompletedEventPublisher.class)
                .size());
    }
}
