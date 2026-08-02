package io.github.monssifechadli99.transactiq.case_search

import java.nio.charset.StandardCharsets
import java.util.Base64
import tools.jackson.databind.ObjectMapper

class SearchCursorCodec(private val mapper:ObjectMapper) {
    data class Cursor(val sort:String,val value:Any,val caseId:String)

    fun encode(sort:FraudCaseSearchSort,values:List<Any>):String {
        require(values.size==2)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mapper.writeValueAsBytes(Cursor(sort.name,values[0],values[1].toString())))
    }

    fun decode(value:String,expectedSort:FraudCaseSearchSort):List<Any> = try {
        val bytes=Base64.getUrlDecoder().decode(value)
        val cursor=mapper.readValue(bytes,Cursor::class.java)
        if(cursor.sort!=expectedSort.name||cursor.caseId.isBlank()||
            (cursor.value !is String&&cursor.value !is Number))
            throw InvalidSearchRequestException("INVALID_CURSOR","Cursor does not match the requested sort")
        listOf(cursor.value,cursor.caseId)
    } catch(error:InvalidSearchRequestException){throw error}
      catch(error:RuntimeException){throw InvalidSearchRequestException("INVALID_CURSOR","Cursor is malformed")}
}
