package local.tickets.companion

import org.json.JSONArray
import org.json.JSONObject

/** Bounded static Android layout metadata. No API accepts node text or descriptions. */
class NativeAuditStructure {
    private val rows = mutableListOf<JSONObject>()
    private var complete = true

    fun observe(parent: Int?, resourceId: String?, className: CharSequence?, visible: Boolean,
                enabled: Boolean, clickable: Boolean, selected: Boolean, checkable: Boolean, checked: Boolean) {
        if (!complete) return
        if (rows.size >= 80 || (parent != null && parent !in rows.indices)) {
            complete = false
            rows.clear()
            return
        }
        // Only conventional static Android resource names, never arbitrary strings.
        val id = resourceId?.takeIf { RESOURCE.matches(it) } ?: ""
        val kind = when (className?.toString()) {
            "android.widget.TextView" -> "text"
            "android.widget.Button" -> "button"
            "android.widget.CheckBox" -> "checkbox"
            "android.widget.EditText" -> "input"
            "android.webkit.WebView" -> "webview"
            "android.widget.ScrollView", "android.widget.HorizontalScrollView" -> "scroll"
            "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.RelativeLayout" -> "container"
            else -> "other"
        }
        rows += JSONObject().put("parent", parent ?: -1).put("resourceId", id).put("kind", kind)
            .put("visible", visible).put("enabled", enabled).put("clickable", clickable)
            .put("selected", selected).put("checkable", checkable).put("checked", checked)
    }

    fun json(): JSONObject = JSONObject().put("complete", complete)
        .put("nodes", JSONArray(if (complete) rows else emptyList<JSONObject>()))
    override fun toString() = "NativeAuditStructure(metadata-only)"

    companion object {
        private val RESOURCE = Regex("cn\\.damai:id/[a-z][a-z0-9_]{0,79}")
    }
}
