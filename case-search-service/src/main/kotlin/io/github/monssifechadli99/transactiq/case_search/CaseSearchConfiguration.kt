package io.github.monssifechadli99.transactiq.case_search

import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.TopicConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(CaseSearchProperties::class)
class CaseSearchConfiguration {
    @Bean fun projectionValidator()=ProjectionValidator()
    @Bean fun projectionDocumentMapper()=ProjectionDocumentMapper()
    @Bean fun openSearchRestClient(p:CaseSearchProperties):RestClient {
        val requests=SimpleClientHttpRequestFactory();requests.setConnectTimeout(p.opensearchRequestTimeout)
        requests.setReadTimeout(p.opensearchRequestTimeout)
        return RestClient.builder().baseUrl(p.opensearchUrl).requestFactory(requests).build()
    }
    @Bean fun openSearchProjectionStore(client:RestClient,mapper:ObjectMapper,p:CaseSearchProperties)=
        OpenSearchProjectionStore(client,mapper,p)
    @Bean fun openSearchIndexInitializer(client:RestClient,p:CaseSearchProperties,mapper:ObjectMapper)=OpenSearchIndexInitializer(client,p,mapper)
    @Bean fun projectionConsumer(v:ProjectionValidator,m:ProjectionDocumentMapper,s:OpenSearchProjectionStore)=
        ProjectionConsumer(v,m,s)
    @Bean fun fraudCaseSearchStore(client:RestClient,mapper:ObjectMapper,p:CaseSearchProperties)=
        FraudCaseSearchStore(client,mapper,p)
    @Bean fun searchCursorCodec(mapper:ObjectMapper)=SearchCursorCodec(mapper)
    @Bean fun projectionTopics(p:CaseSearchProperties)=KafkaAdmin.NewTopics(
        TopicBuilder.name(p.topic).partitions(p.topicPartitions).replicas(1)
            .config(TopicConfig.CLEANUP_POLICY_CONFIG,TopicConfig.CLEANUP_POLICY_COMPACT).build(),
        TopicBuilder.name(p.dltTopic).partitions(p.topicPartitions).replicas(1)
            .config(TopicConfig.CLEANUP_POLICY_CONFIG,TopicConfig.CLEANUP_POLICY_DELETE).build())
    @Bean fun projectionErrorHandler(p:CaseSearchProperties,kafka:KafkaTemplate<Any,Any>):DefaultErrorHandler {
        val publisher=DeadLetterPublishingRecoverer(kafka){record,_->TopicPartition(p.dltTopic,record.partition())}
        publisher.setFailIfSendResultIsError(true)
        publisher.setVerifyPartition(true)
        publisher.excludeHeader(HeadersToAdd.EXCEPTION,HeadersToAdd.EX_CAUSE,HeadersToAdd.EX_MSG,HeadersToAdd.EX_STACKTRACE)
        val handler=DefaultErrorHandler(publisher,FixedBackOff(p.retryInterval.toMillis(),p.retryAttempts-1))
        handler.addNotRetryableExceptions(InvalidProjectionException::class.java,ProjectionIntegrityException::class.java)
        handler.setCommitRecovered(true)
        return handler
    }
}
