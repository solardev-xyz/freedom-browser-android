package baby.freedom.mobile.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in skills (#599): shipped as assets, read only by their exact virtual paths, listed in the prompt. */
class AgentSkillsTest {
    /** The app's own assets, as the build ships them. */
    private val skills = AgentSkills.load { path -> File("src/main/assets/$path").takeIf { it.isFile }?.readBytes() }

    @Test
    fun `the dweb-domains skill ships whole, and its catalog line is desktop's`() {
        for (file in listOf("SKILL.md", "ens.md", "wei.md", "gwei.md")) {
            assertTrue(file, skills.read("/freedom-agent/skills/dweb-domains/$file")!!.isNotBlank())
        }
        val skill = skills.read("/freedom-agent/skills/dweb-domains/SKILL.md")!!
        assertTrue(skill.startsWith("---\nname: dweb-domains\n"))
        // The files the skill points at are the ones it can read.
        for (ref in Regex("/freedom-agent/skills/dweb-domains/[a-z]+\\.md").findAll(skill)) assertTrue(ref.value, skills.read(ref.value) != null)
        val line = skills.catalog()
        assertTrue(line.startsWith("Built-in skills: read the relevant skill file with the read tool before using it. "))
        val list = JSONArray(line.substringAfter("before using it. "))
        assertEquals("dweb-domains", list.getJSONObject(0).getString("name"))
        assertEquals("/freedom-agent/skills/dweb-domains/SKILL.md", list.getJSONObject(0).getString("filePath"))
    }

    @Test
    fun `nothing but a listed skill file can be read`() {
        for (path in listOf(
            "/freedom-agent/skills/dweb-domains/../../../data/data/baby.freedom.mobile/shared_prefs/x.xml",
            "/freedom-agent/skills/dweb-domains/missing.md",
            "agent/system_prompt.md",
            "/freedom-agent/skills/../skills/dweb-domains/../../agent/system_prompt.md",
            "",
        )) {
            assertNull(path, skills.read(path))
        }
        // A path that only normalizes to a listed one is that file.
        assertTrue(skills.read("/freedom-agent/skills/dweb-domains/./ens.md") != null)
    }

    @Test
    fun `read returns a file, or lines of it, and refuses any other path`() = runBlocking {
        val tool = SkillReadTool(skills)
        assertEquals("read", tool.spec.name)
        val all = tool.run(JSONObject().put("path", "/freedom-agent/skills/dweb-domains/wei.md"))
        assertFalse(all.isError)
        assertTrue(all.output.startsWith("# WNS .wei"))
        val one = tool.run(JSONObject().put("path", "/freedom-agent/skills/dweb-domains/wei.md").put("offset", 1).put("limit", 1))
        assertEquals("# WNS .wei", one.output)
        val denied = tool.run(JSONObject().put("path", "/etc/hosts"))
        assertTrue(denied.isError)
        assertTrue(denied.output.startsWith("[POLICY_DENIED]"))
        assertTrue(tool.run(JSONObject().put("path", "/freedom-agent/skills/dweb-domains/wei.md").put("mode", "w")).isError)
        // A chat offers it only when skills ship.
        assertTrue(ToolRegistry.forChat("s", skills = skills) { error("not needed to list the tools") }.specs.any { it.name == "read" })
        assertFalse(ToolRegistry.forChat("s") { error("not needed to list the tools") }.specs.any { it.name == "read" })
    }
}
