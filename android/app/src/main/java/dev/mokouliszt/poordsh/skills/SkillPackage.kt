package dev.mokouliszt.poordsh.skills

import dev.mokouliszt.poordsh.runtime.SafeUnzip
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads an Agent Skill packed as a `.skill` or `.zip` file and checks it the way
 * Anthropic's skill-creator does (scripts/quick_validate.py and package_skill.py),
 * so anything that tool packages is accepted. One check is left out: frontmatter
 * keys skill-creator does not list are kept, not refused. Agents add their own
 * (Claude Code's `user-invocable`, `disable-model-invocation`, `argument-hint`, …)
 * and opencode reads only what it knows, so refusing them would turn away working
 * skills, and keeping a list of every agent's keys would be endless upkeep.
 *
 * Accepted layouts: the skill folder at the top of the archive (what
 * package_skill writes: `<folder>/SKILL.md`), or SKILL.md at the top itself.
 * Plain JVM code, tested on the build host.
 */
object SkillPackage {
    /** [reason] is a stable id for the UI; [message] says what is wrong, in English. */
    class Invalid(val reason: String, message: String) : IOException(message)

    data class Parsed(val name: String, val description: String, val root: File)

    data class Frontmatter(val name: String, val description: String)

    private const val MAX_NAME = 64
    private const val MAX_DESCRIPTION = 1024
    private const val MAX_COMPATIBILITY = 500
    /** Not part of a skill (package_skill leaves them out). */
    private val SKIP_DIRS = setOf("__pycache__", "node_modules")

    /**
     * Unpacks [input] into the empty folder [into] and validates it. On success the
     * skill's own folder (holding SKILL.md) is [Parsed.root], somewhere under [into].
     */
    fun extract(input: InputStream, into: File): Parsed {
        try {
            SafeUnzip.extract(input, into, SKIP_DIRS) { it.endsWith(".pyc") }
        } catch (e: SafeUnzip.Refused) {
            throw Invalid(e.reason, e.message ?: e.reason)
        }
        val root = SafeUnzip.packedRoot(into.canonicalFile) { File(it, "SKILL.md").isFile }
            ?: throw Invalid("no_skill_md", "SKILL.md not found. Pack the skill folder itself (as skill-creator does).")
        // package_skill leaves out evals/ at the skill's top level; it is not part of the skill.
        File(root, "evals").takeIf { it.isDirectory }?.deleteRecursively()
        val skillMds = root.walkTopDown().onEnter { it.name !in SKIP_DIRS }.filter { it.isFile && it.name == "SKILL.md" }.toList()
        if (skillMds.size > 1) {
            val extras = skillMds.filter { it != File(root, "SKILL.md") }.joinToString(", ") { it.relativeTo(root).path }
            throw Invalid("multiple_skill_md", "A skill must contain exactly one SKILL.md, at its top. Extra: $extras.")
        }
        val fm = validate(File(root, "SKILL.md").readText())
        return Parsed(fm.name, fm.description, root)
    }

    /** quick_validate.py's frontmatter checks but the key list, plus a name being required (it names the install). */
    fun validate(skillMd: String): Frontmatter {
        val content = skillMd.replace("\r\n", "\n").removePrefix("﻿")
        if (!content.startsWith("---")) throw Invalid("no_frontmatter", "No YAML frontmatter found in SKILL.md.")
        val match = Regex("^---\n(.*?)\n---", RegexOption.DOT_MATCHES_ALL).find(content)
            ?: throw Invalid("bad_frontmatter", "Invalid frontmatter format in SKILL.md.")
        val yaml = try {
            Load(LoadSettings.builder().setAllowDuplicateKeys(true).build()).loadFromString(match.groupValues[1])
        } catch (e: Exception) {
            throw Invalid("bad_yaml", "Invalid YAML in frontmatter: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val map = yaml as? Map<*, *> ?: throw Invalid("bad_yaml", "Frontmatter must be a YAML dictionary.")
        if ("name" !in map) throw Invalid("no_name", "Missing 'name' in frontmatter.")
        if ("description" !in map) throw Invalid("no_description", "Missing 'description' in frontmatter.")
        val rawName = map["name"] as? String ?: throw Invalid("bad_name", "Name must be a string.")
        val name = rawName.trim()
        if (name.isEmpty()) throw Invalid("bad_name", "Name must not be empty.")
        if (!Regex("^[a-z0-9-]+$").matches(name)) {
            throw Invalid("bad_name", "Name '$name' should be kebab-case (lowercase letters, digits, and hyphens only).")
        }
        if (name.startsWith("-") || name.endsWith("-") || "--" in name) {
            throw Invalid("bad_name", "Name '$name' cannot start/end with hyphen or contain consecutive hyphens.")
        }
        if (name.length > MAX_NAME) throw Invalid("bad_name", "Name is too long (${name.length} characters). Maximum is $MAX_NAME characters.")
        val description = (map["description"] as? String ?: throw Invalid("bad_description", "Description must be a string.")).trim()
        if ('<' in description || '>' in description) {
            throw Invalid("bad_description", "Description cannot contain angle brackets (< or >).")
        }
        if (description.length > MAX_DESCRIPTION) {
            throw Invalid("bad_description", "Description is too long (${description.length} characters). Maximum is $MAX_DESCRIPTION characters.")
        }
        val compatibility = map["compatibility"]
        if (compatibility != null && compatibility != "") {
            if (compatibility !is String) throw Invalid("bad_compatibility", "Compatibility must be a string.")
            if (compatibility.length > MAX_COMPATIBILITY) {
                throw Invalid("bad_compatibility", "Compatibility is too long (${compatibility.length} characters). Maximum is $MAX_COMPATIBILITY characters.")
            }
        }
        return Frontmatter(name, description)
    }
}
