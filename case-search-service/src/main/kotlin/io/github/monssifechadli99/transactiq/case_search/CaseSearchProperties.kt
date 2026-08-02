package io.github.monssifechadli99.transactiq.case_search

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("case-search")
data class CaseSearchProperties @JvmOverloads constructor(val topic:String,val dltTopic:String,val topicPartitions:Int,
    val retryInterval:Duration,val retryAttempts:Long,val opensearchUrl:String,
    val opensearchRequestTimeout:Duration,
    val physicalIndex:String,val readAlias:String,val writeAlias:String,
    val defaultPageSize:Int=20,val maximumPageSize:Int=100)
