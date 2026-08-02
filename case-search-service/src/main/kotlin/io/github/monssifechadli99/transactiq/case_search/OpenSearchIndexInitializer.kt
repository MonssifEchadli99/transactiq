package io.github.monssifechadli99.transactiq.case_search

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode

class OpenSearchIndexInitializer(private val client:RestClient,private val p:CaseSearchProperties,
    private val json:ObjectMapper):InitializingBean {
    override fun afterPropertiesSet(){
        val mapping=ClassPathResource("opensearch/fraud-cases-v1.json").inputStream.bufferedReader().use{it.readText()}
        var created=true
        try { client.put().uri("/{index}",p.physicalIndex).contentType(MediaType.APPLICATION_JSON)
            .body(mapping).retrieve().toBodilessEntity() }
        catch(e:HttpClientErrorException.BadRequest){
            if(!e.responseBodyAsString.contains("resource_already_exists_exception")) throw e
            created=false
        }
        verifyMapping(mapping)
        if(created){
            val aliases="""{"actions":[{"add":{"index":"${p.physicalIndex}","alias":"${p.readAlias}"}},
              {"add":{"index":"${p.physicalIndex}","alias":"${p.writeAlias}","is_write_index":true}}]}"""
            client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON).body(aliases).retrieve().toBodilessEntity()
        }
        verifyAliases()
    }
    private fun verifyMapping(expectedJson:String){
        val expected=json.readTree(expectedJson)["mappings"]
        val body=client.get().uri("/{index}/_mapping",p.physicalIndex).retrieve().body(String::class.java).orEmpty()
        val actual=json.readTree(body)[p.physicalIndex]?.get("mappings")
        require(equivalent(actual,expected)){"Existing OpenSearch index mapping is incompatible with the Fraud Case v1 mapping"}
    }
    private fun verifyAliases(){
        val body=try{client.get().uri("/_alias/{read},{write}",p.readAlias,p.writeAlias)
            .retrieve().body(String::class.java).orEmpty()}
        catch(error:HttpClientErrorException.NotFound){throw IllegalStateException("Required Fraud Case aliases are missing",error)}
        val indices=json.readTree(body)
        require(indices.size()==1&&indices.has(p.physicalIndex)){"Fraud Case aliases have unexpected index targets"}
        val aliases=indices[p.physicalIndex]["aliases"]
        require(aliases.has(p.readAlias)&&aliases.has(p.writeAlias)){"Required Fraud Case aliases are missing"}
        require(aliases[p.writeAlias]["is_write_index"]?.asBoolean()==true){"Fraud Case write alias is not the designated write index"}
    }
    private fun equivalent(actual:JsonNode?,expected:JsonNode?):Boolean {
        if(actual==null||expected==null)return actual==expected
        if(actual.isNumber&&expected.isNumber)return actual.decimalValue().compareTo(expected.decimalValue())==0
        if(actual.isObject&&expected.isObject){
            val actualNames=actual.propertyNames().asSequence().toSet()
            val expectedNames=expected.propertyNames().asSequence().toSet()
            return actualNames==expectedNames&&actualNames.all{equivalent(actual[it],expected[it])}
        }
        if(actual.isArray&&expected.isArray)return actual.size()==expected.size()&&(0 until actual.size()).all{equivalent(actual[it],expected[it])}
        return actual==expected
    }
}
