package io.github.monssifechadli99.transactiq.authorization.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = {
    "fraud.grpc.client.enabled=false",
    "authorization.outbox.publisher.enabled=false"
})
@Import({
    PostgreSqlTestcontainersConfiguration.class,
    FraudAssessmentTestConfiguration.class
})
public @interface AuthorizationServiceIntegrationTest {

    @AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
    SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;
}
