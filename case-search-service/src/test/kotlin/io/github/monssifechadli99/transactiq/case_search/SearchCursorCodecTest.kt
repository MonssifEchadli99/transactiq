package io.github.monssifechadli99.transactiq.case_search

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class SearchCursorCodecTest {
    private val codec=SearchCursorCodec(JsonMapper.builder().build())
    @Test fun `cursor round trips and is opaque`() {
        val cursor=codec.encode(FraudCaseSearchSort.CREATED_AT_DESC,listOf("2026-08-01T10:00:00Z","case-2"))
        assertFalse(cursor.contains("2026"))
        assertEquals(listOf("2026-08-01T10:00:00Z","case-2"),codec.decode(cursor,FraudCaseSearchSort.CREATED_AT_DESC))
    }
    @Test fun `malformed and sort-incompatible cursors are rejected`() {
        assertThrows(InvalidSearchRequestException::class.java){codec.decode("not-base64",FraudCaseSearchSort.CREATED_AT_DESC)}
        val cursor=codec.encode(FraudCaseSearchSort.CREATED_AT_DESC,listOf("2026-08-01T10:00:00Z","case-2"))
        assertThrows(InvalidSearchRequestException::class.java){codec.decode(cursor,FraudCaseSearchSort.UPDATED_AT_ASC)}
    }
}
