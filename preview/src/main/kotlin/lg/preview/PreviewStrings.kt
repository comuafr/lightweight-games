package lg.preview

// Dev-only tool; see Java2DRenderer.kt header. Resolves lg.core.scene.StringKey
// ordinals to their English text by parsing androidApp's strings.xml at
// runtime, so preview PNGs show real copy instead of bare enum names.

import lg.core.scene.StringKey
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Maps [StringKey] ordinals to English text loaded from
 * `androidApp/src/main/res/values/strings.xml` (key = lower_snake_case of the
 * enum name, e.g. `TITLE_SNAKE` -> `title_snake`). Falls back to the enum
 * name itself when a key is missing from the resource file (e.g. a
 * newly-registered game whose Android strings haven't landed yet).
 */
internal class PreviewStrings private constructor(private val byName: Map<String, String>) {
    fun get(ordinal: Int): String {
        val key = StringKey.entries.getOrNull(ordinal) ?: return "?"
        return byName[toResourceName(key.name)] ?: key.name
    }

    private fun toResourceName(enumName: String): String = enumName.lowercase()

    companion object {
        /** Loads `strings.xml`, searching upward from the current working directory. */
        fun load(): PreviewStrings {
            val file = findStringsXml()
            if (file == null) {
                System.err.println(
                    "preview: could not locate androidApp/src/main/res/values/strings.xml; " +
                        "falling back to raw StringKey names for every string.",
                )
                return PreviewStrings(emptyMap())
            }
            return PreviewStrings(parse(file))
        }

        private fun findStringsXml(): File? {
            val relative = "androidApp/src/main/res/values/strings.xml"
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            var depth = 0
            while (dir != null && depth < 8) {
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
                depth++
            }
            return null
        }

        private fun parse(file: File): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = file.inputStream().use { builder.parse(it) }
            val nodes = doc.getElementsByTagName("string")
            val out = HashMap<String, String>(nodes.length * 2)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name") ?: continue
                val text = el.textContent ?: ""
                out[name] = unescape(text)
            }
            return out
        }

        /** Minimal Android string-resource unescaping for the handful of escapes used here. */
        private fun unescape(raw: String): String =
            raw.trim()
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
    }
}
