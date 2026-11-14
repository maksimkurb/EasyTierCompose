package cc.ptoe.easytier.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards translation completeness: every resource in `values/` (English, the default) must
 * exist in each fully translated locale, with the same type, the same format placeholders and
 * the plural quantities that locale needs. Also keeps `locales_config.xml` in sync with the
 * `values-*` directories.
 */
class LocalizationTest {

    private sealed interface Res {
        data class Str(val text: String) : Res
        data class Plural(val items: Map<String, String>) : Res
    }

    private val resDir: File = listOf("src/main/res", "app/src/main/res")
        .map(::File).first { it.isDirectory }

    /** Locales that must be 100% translated, with the plural quantities each language uses. */
    private val completeLocales = mapOf(
        "values-ru" to setOf("one", "few", "many", "other"),
        "values-zh-rCN" to setOf("other"),
    )

    private val defaultQuantities = setOf("one", "other")

    private fun load(dir: String): Map<String, Res> {
        val file = File(resDir, "$dir/strings.xml")
        assertTrue("Missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, Res>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.getAttribute("translatable") == "false") continue
            val name = el.getAttribute("name")
            val previous = when (el.tagName) {
                "string" -> out.put(name, Res.Str(el.textContent))
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    out.put(
                        name,
                        Res.Plural(
                            (0 until items.length).associate {
                                val item = items.item(it) as Element
                                item.getAttribute("quantity") to item.textContent
                            },
                        ),
                    )
                }
                else -> null
            }
            assertTrue("Duplicate resource '$name' in $dir", previous == null)
        }
        return out
    }

    /** Format specifiers in order-independent form, e.g. `1$s`, `2$d`, `1$.1f`. `%%` is ignored. */
    private fun placeholders(text: String): List<String> {
        var implicit = 0
        return Regex("%(\\d+\\$)?([-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%])").findAll(text)
            .filter { it.groupValues[2] != "%" }
            .map { m ->
                val index = m.groupValues[1].ifEmpty { "${++implicit}$" }
                index + m.groupValues[2]
            }
            .sorted().toList()
    }

    @Test
    fun defaultPluralsHaveRequiredQuantities() {
        load("values").forEach { (name, res) ->
            if (res is Res.Plural) {
                assertTrue("values/$name must define $defaultQuantities", res.items.keys.containsAll(defaultQuantities))
            }
        }
    }

    @Test
    fun translationsAreComplete() {
        val base = load("values")
        for ((dir, quantities) in completeLocales) {
            val tr = load(dir)
            val missing = base.keys - tr.keys
            val extra = tr.keys - base.keys
            assertTrue("$dir is missing: $missing", missing.isEmpty())
            assertTrue("$dir has keys that do not exist in values/: $extra", extra.isEmpty())
            for ((name, source) in base) {
                when (source) {
                    is Res.Str -> {
                        val t = tr.getValue(name)
                        assertTrue("$dir/$name must be a <string>", t is Res.Str)
                        assertTrue("$dir/$name is empty", (t as Res.Str).text.isNotBlank())
                        assertEquals("$dir/$name placeholders", placeholders(source.text), placeholders(t.text))
                    }
                    is Res.Plural -> {
                        val t = tr.getValue(name)
                        assertTrue("$dir/$name must be <plurals>", t is Res.Plural)
                        t as Res.Plural
                        assertEquals("$dir/$name quantities", quantities, t.items.keys)
                        val expected = placeholders(source.items.getValue("other"))
                        t.items.forEach { (q, text) ->
                            assertEquals("$dir/$name[$q] placeholders", expected, placeholders(text))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun localesConfigMatchesResourceDirectories() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val declared = (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttribute("android:name") }
            .toSet()
        val shipped = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }
            .orEmpty()
            .map { it.name.removePrefix("values-").replace("-r", "-") }
            .toSet() + "en"
        assertEquals("locales_config.xml vs values-* directories", shipped, declared)
    }
}
