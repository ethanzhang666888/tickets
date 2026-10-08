package local.tickets.companion

import org.junit.Assert.*
import org.junit.Test

class NativeAuditStructureTest {
    private fun add(audit: NativeAuditStructure, parent: Int? = null, id: String? = null,
                    type: String = "android.widget.TextView") =
        audit.observe(parent, id, type, true, true, false, true, false, false)

    @Test fun exportsStaticMetadataAndNoArbitraryIdentifiersOrClasses() {
        val audit = NativeAuditStructure()
        add(audit, id = "cn.damai:id/sku_item")
        add(audit, 0, "cn.damai:id/姓名1234", "private.dynamic.Class")
        add(audit, 0, "other:id/node")
        val json = audit.json()
        val rows = json.getJSONArray("nodes")
        assertTrue(json.getBoolean("complete"))
        assertEquals("cn.damai:id/sku_item", rows.getJSONObject(0).getString("resourceId"))
        assertEquals(0, rows.getJSONObject(1).getInt("parent"))
        assertEquals("", rows.getJSONObject(1).getString("resourceId"))
        assertEquals("other", rows.getJSONObject(1).getString("kind"))
        assertEquals("", rows.getJSONObject(2).getString("resourceId"))
        assertFalse(json.toString().contains("姓名"))
        assertFalse(json.toString().contains("private"))
    }

    @Test fun invalidParentsOrOverflowDiscardAllPartialEvidence() {
        for (parent in listOf(-1, 0, 2)) {
            val audit = NativeAuditStructure()
            add(audit, parent)
            assertFalse(audit.json().getBoolean("complete"))
            assertEquals(0, audit.json().getJSONArray("nodes").length())
        }
        val audit = NativeAuditStructure()
        repeat(80) { add(audit) }
        assertTrue(audit.json().getBoolean("complete"))
        add(audit)
        assertFalse(audit.json().getBoolean("complete"))
        assertEquals(0, audit.json().getJSONArray("nodes").length())
    }
}
