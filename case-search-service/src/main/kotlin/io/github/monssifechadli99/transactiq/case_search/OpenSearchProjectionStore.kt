package io.github.monssifechadli99.transactiq.case_search

import tools.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient

class OpenSearchProjectionStore(private val client:RestClient,private val mapper:ObjectMapper,
    private val properties:CaseSearchProperties) {
    fun apply(caseId:String,document:Map<String,Any>) {
        repeat(5) {
            val current=read(caseId)
            if(current!=null){
                val stored=(current.source["aggregateVersion"] as Number).toLong()
                val incoming=(document["aggregateVersion"] as Number).toLong()
                if(stored>incoming)return
                if(stored==incoming){
                    if(current.source["snapshotHash"]==document["snapshotHash"])return
                    throw ProjectionIntegrityException("same aggregate version has a different snapshot hash")
                }
            }
            try {
                val uri=if(current==null) "/{alias}/_doc/{id}?op_type=create&refresh=wait_for"
                    else "/{alias}/_doc/{id}?if_seq_no=${current.sequence}&if_primary_term=${current.primaryTerm}&refresh=wait_for"
                client.put().uri(uri,properties.writeAlias,caseId).contentType(MediaType.APPLICATION_JSON)
                    .body(document).retrieve().toBodilessEntity();return
            } catch(conflict:org.springframework.web.client.HttpClientErrorException.Conflict){ /* bounded CAS retry */ }
            catch(error:RuntimeException){throw OpenSearchUnavailableException("OpenSearch projection write failed",error)}
        }
        throw OpenSearchUnavailableException("OpenSearch projection write remained concurrent after five attempts")
    }
    private fun read(caseId:String):Current?=try{
        val json=client.get().uri("/{alias}/_doc/{id}",properties.readAlias,caseId).retrieve().body(String::class.java)!!
        @Suppress("UNCHECKED_CAST") val value=mapper.readValue(json,Map::class.java) as Map<String,Any>
        @Suppress("UNCHECKED_CAST") Current((value["_source"] as Map<String,Any>),
            (value["_seq_no"] as Number).toLong(),(value["_primary_term"] as Number).toLong())
    }catch(notFound:org.springframework.web.client.HttpClientErrorException.NotFound){null}
     catch(error:RuntimeException){throw OpenSearchUnavailableException("OpenSearch projection read failed",error)}
    private data class Current(val source:Map<String,Any>,val sequence:Long,val primaryTerm:Long)
}
