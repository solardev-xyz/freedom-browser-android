package baby.freedom.mobile.agent

import java.nio.file.Paths
import org.json.JSONArray
import org.json.JSONObject

/**
 * Freedom Agent's built-in skills (desktop's `builtin-skills.js`): reviewed
 * Markdown shipped in the app's assets under `agent/skills/`, each file
 * read only by its exact virtual path under [ROOT] — the paths desktop's
 * skill text refers to (`/freedom-agent/skills/dweb-domains/ens.md`) —
 * and nothing else. Android ships `dweb-domains` (#599), for the
 * `domain_*` tools.
 */
internal class AgentSkills(private val files: Map<String, String>) {
    class Skill(val name: String, val description: String, val files: List<String>)

    /** The catalog the system prompt lists (desktop's line, word for word). */
    fun catalog(): String {
        val list = JSONArray()
        for (skill in SKILLS) {
            if (path(skill.name, "SKILL.md") !in files) continue
            list.put(JSONObject().put("name", skill.name).put("description", skill.description).put("filePath", path(skill.name, "SKILL.md")))
        }
        return "Built-in skills: read the relevant skill file with the read tool before using it. $list"
    }

    /** The text at virtual [path] (normalized first, so `..` can't step out), or null for any other path. */
    fun read(path: String): String? {
        if (path.isEmpty() || path.length > 512 || '\u0000' in path) return null
        val normalized = try {
            Paths.get(path).normalize().toString()
        } catch (e: Exception) {
            return null
        }
        return files[normalized]
    }

    val isEmpty: Boolean get() = files.isEmpty()

    companion object {
        const val ROOT = "/freedom-agent/skills"

        /** Desktop's bound on one skill file (`MAX_BUILTIN_SKILL_BYTES`). */
        const val MAX_BYTES = 64 * 1024

        val SKILLS = listOf(
            Skill(
                "dweb-domains",
                "Register or renew .eth, .wei and .gwei names, manage website/address/text records, and connect a verified Swarm " +
                    "publication to an owned name through Freedom’s wallet.",
                listOf("SKILL.md", "ens.md", "wei.md", "gwei.md"),
            ),
        )

        fun path(skill: String, file: String) = "$ROOT/$skill/$file"

        /** Every skill file, through [open] (an asset path under `agent/skills/` to its bytes); a missing or oversized one is left out. */
        fun load(open: (String) -> ByteArray?): AgentSkills {
            val files = HashMap<String, String>()
            for (skill in SKILLS) {
                for (file in skill.files) {
                    val bytes = try {
                        open("agent/skills/${skill.name}/$file")
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    if (bytes.size > MAX_BYTES) continue
                    files[path(skill.name, file)] = bytes.toString(Charsets.UTF_8)
                }
            }
            return AgentSkills(files)
        }
    }
}

/**
 * Desktop's `read` as Freedom Agent has it (`createBuiltInSkillReadTool`):
 * one built-in skill file by its exact virtual path from the catalog, with
 * Pi's optional 1-based [offset] line and [limit] of lines. Nothing on
 * the phone outside those files can be read.
 */
internal class SkillReadTool(private val skills: AgentSkills) : AgentTool {
    override val spec = ToolSpec(
        name = "read",
        description = "Read one reviewed built-in Freedom Agent skill using an exact virtual path from the available skills catalog. " +
            "Host files and unlisted paths are unavailable.",
        parameters = JSONObject(
            """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Path to the file to read"},
              "offset":{"type":"number","description":"Line number to start reading from (1-indexed)"},
              "limit":{"type":"number","description":"Maximum number of lines to read"}
            },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )

    override suspend fun run(arguments: JSONObject): ToolOutcome {
        arguments.keys().forEach {
            if (it !in setOf("path", "offset", "limit")) return ToolError(ToolError.INVALID_ARGUMENT, "$it is not a read argument").outcome("read")
        }
        val path = arguments.opt("path") as? String
            ?: return ToolError(ToolError.INVALID_ARGUMENT, "path must be a string").outcome("read")
        val text = skills.read(path)
            ?: return ToolError(ToolError.POLICY_DENIED, "Freedom Agent can read only bundled skill files").outcome("read")
        val offset = (arguments.opt("offset") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val limit = (arguments.opt("limit") as? Number)?.toInt()?.coerceAtLeast(1)
        val lines = text.lines()
        if (offset > lines.size) return ToolError(ToolError.INVALID_ARGUMENT, "Offset $offset is beyond end of file (${lines.size} lines total)").outcome("read")
        val end = if (limit == null) lines.size else minOf(lines.size, offset - 1 + limit)
        return ToolOutcome(lines.subList(offset - 1, end).joinToString("\n"))
    }
}
