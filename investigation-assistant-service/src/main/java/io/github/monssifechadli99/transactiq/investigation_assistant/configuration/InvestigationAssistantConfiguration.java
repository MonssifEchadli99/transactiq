package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.kafka.InvestigationProjectionConsumer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationApiMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationAnswerApiMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch.OpenSearchEvidenceIndexInitializer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch.OpenSearchEvidenceIndexStore;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch.OpenSearchEvidenceRetrievalStore;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationAnswerService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionIngestionService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.SafeEvidenceMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EmbeddingDimensionMismatchException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvalidProjectionException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ProjectionIntegrityException;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties({
    InvestigationConsumerProperties.class,
    InvestigationOpenSearchProperties.class,
    InvestigationEmbeddingProperties.class,
    InvestigationRetrievalProperties.class
})
public class InvestigationAssistantConfiguration {

    @Bean
    ProjectionValidator projectionValidator() {
        return new ProjectionValidator();
    }

    @Bean
    SafeEvidenceMapper safeEvidenceMapper() {
        return new SafeEvidenceMapper();
    }

    @Bean
    InvestigationApiMapper investigationApiMapper() {
        return new InvestigationApiMapper();
    }

    @Bean
    InvestigationAnswerApiMapper investigationAnswerApiMapper() {
        return new InvestigationAnswerApiMapper();
    }

    @Bean
    RestClient investigationOpenSearchRestClient(InvestigationOpenSearchProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().baseUrl(properties.url()).requestFactory(requestFactory).build();
    }

    @Bean
    OpenSearchEvidenceIndexInitializer openSearchEvidenceIndexInitializer(
            RestClient investigationOpenSearchRestClient,
            InvestigationOpenSearchProperties properties,
            ObjectMapper mapper) {
        return new OpenSearchEvidenceIndexInitializer(investigationOpenSearchRestClient, properties, mapper);
    }

    @Bean
    EvidenceIndexPort evidenceIndexPort(
            RestClient investigationOpenSearchRestClient,
            ObjectMapper mapper,
            InvestigationOpenSearchProperties properties) {
        return new OpenSearchEvidenceIndexStore(investigationOpenSearchRestClient, mapper, properties);
    }

    @Bean
    EvidenceRetrievalPort evidenceRetrievalPort(
            RestClient investigationOpenSearchRestClient,
            ObjectMapper mapper,
            InvestigationOpenSearchProperties properties) {
        return new OpenSearchEvidenceRetrievalStore(investigationOpenSearchRestClient, mapper, properties);
    }

    @Bean
    ProjectionIngestionService projectionIngestionService(
            SafeEvidenceMapper safeEvidenceMapper,
            EmbeddingPort embeddingPort,
            EvidenceIndexPort evidenceIndexPort,
            InvestigationEmbeddingProperties embeddingProperties) {
        return new ProjectionIngestionService(
                safeEvidenceMapper, embeddingPort, evidenceIndexPort, embeddingProperties.expectedDimensions());
    }

    @Bean
    InvestigationRetrievalService investigationRetrievalService(
            EvidenceRetrievalPort evidenceRetrievalPort,
            EmbeddingPort embeddingPort,
            InvestigationRetrievalProperties retrievalProperties,
            PortfolioMetrics metrics) {
        return new InvestigationRetrievalService(
                evidenceRetrievalPort,
                embeddingPort,
                retrievalProperties.candidatePoolSize(),
                retrievalProperties.focalTextMaxLength(),
                retrievalProperties.excerptMaxLength(),
                metrics);
    }

    @Bean
    InvestigationAnswerService investigationAnswerService(
            InvestigationRetrievalService investigationRetrievalService,
            ChatGenerationPort chatGenerationPort,
            PortfolioMetrics metrics) {
        return new InvestigationAnswerService(investigationRetrievalService, chatGenerationPort, metrics);
    }

    @Bean
    InvestigationProjectionConsumer investigationProjectionConsumer(
            ProjectionValidator projectionValidator, ProjectionIngestionService projectionIngestionService) {
        return new InvestigationProjectionConsumer(projectionValidator, projectionIngestionService);
    }

    @Bean
    KafkaAdmin.NewTopics investigationAssistantTopics(InvestigationConsumerProperties properties) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(properties.topic()).partitions(properties.topicPartitions()).replicas(1)
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT).build(),
                TopicBuilder.name(properties.dltTopic()).partitions(properties.topicPartitions()).replicas(1)
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE).build());
    }

    @Bean
    DefaultErrorHandler investigationAssistantConsumerErrorHandler(
            InvestigationConsumerProperties properties, KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(properties.dltTopic(), record.partition()));
        publisher.setFailIfSendResultIsError(true);
        publisher.setVerifyPartition(true);
        publisher.excludeHeader(
                HeadersToAdd.EXCEPTION, HeadersToAdd.EX_CAUSE, HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        DefaultErrorHandler handler = new DefaultErrorHandler(
                publisher, new FixedBackOff(properties.retryInterval().toMillis(), properties.retryAttempts() - 1));
        handler.addNotRetryableExceptions(
                InvalidProjectionException.class,
                ProjectionIntegrityException.class,
                EmbeddingDimensionMismatchException.class);
        // RECORD acknowledgment commits a successfully recovered record after the
        // handler returns. A failed DLT future makes the recoverer throw, so this
        // explicit policy leaves the source offset uncommitted for redelivery.
        handler.setAckAfterHandle(true);
        return handler;
    }
}
