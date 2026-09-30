package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.OpenSourceLicences.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSourceLicencesTest {
    private val json = """
        {
          "components": [
            {"section": "native", "name": "ring", "id": "ring", "version": "0.17.14", "licence": "Apache-2.0 AND ISC", "url": "https://crates.io/crates/ring/0.17.14", "texts": [0, 1]},
            {"section": "data", "name": "EasyList", "id": "EasyList", "version": "", "licence": "CC-BY-SA-3.0", "url": "https://easylist.to/", "notice": "© The EasyList authors", "texts": [2]},
            {"section": "android", "name": "Activity", "id": "androidx.activity:activity", "version": "1.10.0", "licence": "Apache-2.0", "url": "", "texts": [0]},
            {"section": "android", "name": "annotation", "id": "androidx.annotation:annotation", "version": "1.9.1", "licence": "Apache-2.0", "url": "", "texts": [0]}
          ],
          "texts": [
            {"title": "Apache License 2.0", "text": "Apache text\n"},
            {"title": "ISC License", "text": "ISC text\n"},
            {"title": "CC-BY-SA-3.0", "text": "CC text\n"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses and orders by section, then name ignoring case`() {
        val licences = OpenSourceLicences.parse(json)
        assertEquals(listOf("Activity", "annotation", "ring", "EasyList"), licences.components.map { it.name })
        assertEquals(
            listOf(Section.Android, Section.Android, Section.Native, Section.Data),
            licences.components.map { it.section },
        )
        val ring = licences.components.single { it.name == "ring" }
        assertEquals(listOf("Apache License 2.0", "ISC License"), licences.textsOf(ring).map { it.title })
        val easyList = licences.components.single { it.name == "EasyList" }
        assertEquals("© The EasyList authors", easyList.notice)
        assertEquals("", easyList.version)
        assertEquals(null, ring.notice)
    }

    @Test
    fun `a component without a text, or pointing past the texts, is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenSourceLicences.parse(json.replace("\"texts\": [2]", "\"texts\": []"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenSourceLicences.parse(json.replace("\"texts\": [2]", "\"texts\": [7]"))
        }
    }

    @Test
    fun `search matches name, id, version and licence, ignoring case`() {
        val all = OpenSourceLicences.parse(json).components
        assertEquals(all, OpenSourceLicences.search(all, "  "))
        assertEquals(listOf("ring"), OpenSourceLicences.search(all, "RING").map { it.name })
        assertEquals(listOf("Activity"), OpenSourceLicences.search(all, "androidx.activity").map { it.name })
        assertEquals(listOf("ring"), OpenSourceLicences.search(all, "isc").map { it.name })
        assertEquals(listOf("annotation"), OpenSourceLicences.search(all, "1.9.1").map { it.name })
        assertEquals(emptyList<String>(), OpenSourceLicences.search(all, "nothing").map { it.name })
    }

    @Test
    fun `paragraphs are reflowed, list items keep their own line`() {
        val text = """
                                 Apache License
                           Version 2.0, January 2004

   1. Definitions.

      "License" shall mean the terms and conditions for use,
      reproduction, and distribution.

      (a) You must give any other recipients a copy; and
      (b) You must cause any modified files to carry
          prominent notices.

Copyright (c) 2023 Someone
Inc. and friends
        """.trimIndent()
        assertEquals(
            listOf(
                "Apache License Version 2.0, January 2004",
                "1. Definitions.",
                "\"License\" shall mean the terms and conditions for use, reproduction, and distribution.",
                "(a) You must give any other recipients a copy; and\n(b) You must cause any modified files to carry prominent notices.",
                "Copyright (c) 2023 Someone Inc. and friends",
            ),
            OpenSourceLicences.paragraphs(text),
        )
    }
}
