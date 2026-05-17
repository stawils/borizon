package com.borizon.app.skills

import android.content.Context
import android.net.Uri
import android.util.Log
import com.borizon.app.util.debugLog
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.documentfile.provider.DocumentFile
import com.borizon.app.data.SkillSettingsSerializer
import com.borizon.app.proto.Skill
import com.borizon.app.proto.Skills
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Manages skill lifecycle: loading from assets, parsing SKILL.md, persistence, and selection.
 *
 * simplified to a plain class (not ViewModel).
 * Skills are loaded from bundled assets and optionally from imported directories.
 * Selection state is persisted via Proto DataStore.
 */
class SkillManager(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"
        private const val SKILLS_DIR = "skills"
        private const val SKILL_MD = "SKILL.md"
        private const val SCRIPTS_DIR = "scripts"
        private const val LOCAL_URL_BASE = "https://appassets.androidplatform.net"

        /** Sanitize a name for use as a directory or file name. Strips path separators and traversal sequences. */
        internal fun sanitizeDirName(name: String): String {
            return name.lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), "")
                .replace("\\s+".toRegex(), "-")
                .trim('-', '.')
                .take(64)
                .ifBlank { "unnamed" }
        }
    }

    private val skillDataStore: DataStore<Skills> = DataStoreFactory.create(
        serializer = SkillSettingsSerializer,
        produceFile = { context.dataStoreFile("skills.pb") },
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _skills: StateFlow<List<Skill>> = skillDataStore.data
        .map { it.skillsList }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    val skills: StateFlow<List<Skill>> = _skills

    /**
     * Load skills from bundled assets and reconcile with persisted selection state.
     * Call once at app init before ReflectAgent.initConversation().
     */
    suspend fun loadSkills() {
        // 1. Load persisted state
        val persisted = skillDataStore.data.first()
        val persistedMap = persisted.skillsList.associateBy { it.name }

        // 2. Scan bundled assets (skills are under assets/skills/)
        val assetSkills = mutableListOf<Skill>()
        val skillDirs = context.assets.list("skills")?.toList() ?: emptyList()

        for (dir in skillDirs) {
            try {
                val mdContent = context.assets.open("skills/$dir/$SKILL_MD")
                    .bufferedReader().use { it.readText() }
                val parsed = parseSkillMd(mdContent, builtIn = true)
                if (parsed != null) {
                    // Preserve selection state from DataStore
                    val wasSelected = persistedMap[parsed.name]?.selected ?: true
                    assetSkills.add(parsed.toBuilder().setSelected(wasSelected).build())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse skill from assets/$dir", e)
            }
        }

        // 3. Include imported skills (non-built-in from persisted state)
        val importedSkills = persisted.skillsList.filter { !it.builtIn }
        val allSkills = assetSkills + importedSkills

        // 4. Persist merged state
        skillDataStore.updateData { current ->
            val builder = Skills.newBuilder()
            allSkills.forEach { builder.addSkills(it) }
            builder.build()
        }

        debugLog(TAG, "Loaded ${allSkills.size} skills (${assetSkills.size} built-in, ${importedSkills.size} imported)")
    }

    /**
     * Parse a SKILL.md file into a Skill proto.
     * Format: triple-dash frontmatter with YAML name/description, then markdown body as instructions.
     * Returns null if parsing fails.
     */
    internal fun parseSkillMd(mdContent: String, builtIn: Boolean): Skill? {
        val trimmed = mdContent.trim()
        if (!trimmed.startsWith("---")) {
            Log.w(TAG, "SKILL.md does not start with frontmatter")
            return null
        }

        // Split on triple-dash boundaries
        val parts = trimmed.split("---")
        if (parts.size < 3) {
            Log.w(TAG, "SKILL.md has fewer than 3 parts after splitting on ---")
            return null
        }

        val frontmatter = parts[1].trim()
        val instructions = parts.drop(2).joinToString("---").trim()

        // Parse simple YAML key: value pairs
        var name = ""
        var description = ""
        for (line in frontmatter.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()
            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }

        if (name.isBlank()) {
            Log.w(TAG, "SKILL.md missing name in frontmatter")
            return null
        }

        if (name.length > 64) {
            Log.w(TAG, "SKILL.md name too long: ${name.length}")
            return null
        }

        if (instructions.isBlank()) {
            Log.w(TAG, "SKILL.md has no instructions body")
            return null
        }

        return Skill.newBuilder()
            .setName(name)
            .setDescription(description)
            .setInstructions(instructions)
            .setBuiltIn(builtIn)
            .setSelected(true)
            .build()
    }

    /** Return only skills that are selected (enabled by the user). */
    fun getSelectedSkills(): List<Skill> = _skills.value.filter { it.selected }

    /** Get a skill by exact name match. */
    fun getSkillByName(name: String): Skill? = _skills.value.find { it.name == name }

    /**
     * Format selected skills as a bullet list for the system prompt.
     * Only names and descriptions — full instructions are loaded on demand via loadSkill.
     */
    fun getSkillsListForPrompt(): String {
        val selected = getSelectedSkills()
        if (selected.isEmpty()) return ""
        return selected.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    /** Toggle a skill's selected state and persist the change. */
    suspend fun setSkillSelected(name: String, selected: Boolean) {
        skillDataStore.updateData { current ->
            val builder = Skills.newBuilder()
            for (skill in current.skillsList) {
                builder.addSkills(
                    if (skill.name == name) skill.toBuilder().setSelected(selected).build()
                    else skill
                )
            }
            builder.build()
        }
    }

    /**
     * Build the WebView URL for a JS skill's script.
     * Built-in skills load from assets, imported skills from internal storage.
     * Returns null if the skill doesn't exist or has no JS scripts.
     */
    fun getJsSkillUrl(skillName: String, scriptName: String): String? {
        if (skillName.contains('/') || skillName.contains('\\') || scriptName.contains('/') || scriptName.contains('\\')) return null
        val skill = getSkillByName(skillName) ?: return null

        return if (skill.builtIn) {
            // Built-in: serve from assets via WebViewAssetLoader
            val hasScript = try {
                context.assets.list("skills/$skillName/$SCRIPTS_DIR")?.contains(scriptName) == true
            } catch (_: Exception) { false }

            if (hasScript) "$LOCAL_URL_BASE/assets/skills/$skillName/$SCRIPTS_DIR/$scriptName"
            else null
        } else {
            // Imported: serve from internal storage
            val importDir = skill.importDirName
            if (importDir.isBlank()) return null
            val scriptFile = File(context.filesDir, "$SKILLS_DIR/$importDir/$SCRIPTS_DIR/$scriptName")
            if (scriptFile.exists()) "$LOCAL_URL_BASE/$SKILLS_DIR/$importDir/$SCRIPTS_DIR/$scriptName"
            else null
        }
    }

    /** Resolve a relative webview URL from a JS skill result to a full loadable URL. */
    fun getJsSkillWebviewUrl(skillName: String, relativeUrl: String): String {
        if (skillName.contains('/') || skillName.contains('\\')) return ""
        if (relativeUrl.startsWith("http")) return ""
        if (relativeUrl.contains("..") || relativeUrl.startsWith("/")) return ""
        val skill = getSkillByName(skillName)
        return if (skill != null && skill.builtIn) {
            "$LOCAL_URL_BASE/assets/skills/$skillName/assets/$relativeUrl"
        } else {
            val importDir = skill?.importDirName ?: ""
            "$LOCAL_URL_BASE/$SKILLS_DIR/$importDir/assets/$relativeUrl"
        }
    }

    /**
     * Import a skill from a directory selected via SAF (Storage Access Framework).
     * Expects a SKILL.md in the selected directory, optionally with a scripts/ subdirectory.
     */
    suspend fun importSkillFromDirectory(directoryUri: Uri): Result<String> {
        return try {
            val pickedDir = DocumentFile.fromTreeUri(context, directoryUri) ?: return Result.failure(
                Exception("Could not access directory")
            )

            // Find SKILL.md
            val skillMdFile = pickedDir.findFile(SKILL_MD)
                ?: return Result.failure(Exception("No $SKILL_MD found in selected directory"))

            val mdContent = context.contentResolver.openInputStream(skillMdFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return Result.failure(Exception("Could not read $SKILL_MD"))

            val parsed = parseSkillMd(mdContent, builtIn = false)
                ?: return Result.failure(Exception("Invalid $SKILL_MD format"))

            // Check for name conflict
            if (getSkillByName(parsed.name) != null) {
                return Result.failure(Exception("Skill '${parsed.name}' already exists"))
            }

            // Create import directory (sanitized name)
            val importDirName = sanitizeDirName(parsed.name)
            val importDir = File(context.filesDir, "$SKILLS_DIR/$importDirName")
            if (importDir.exists() && importDir.isDirectory) {
                val existingMd = File(importDir, SKILL_MD)
                if (existingMd.exists()) {
                    val existing = parseSkillMd(existingMd.readText(), false)
                    if (existing != null && existing.name != parsed.name) {
                        return Result.failure(IllegalArgumentException("Directory '$importDirName' already used by skill '${existing.name}'"))
                    }
                }
            }
            importDir.mkdirs()

            // Copy SKILL.md
            val targetMd = File(importDir, SKILL_MD)
            targetMd.writeText(mdContent)

            // Copy scripts/ if present
            val scriptsDir = pickedDir.findFile(SCRIPTS_DIR)
            if (scriptsDir != null && scriptsDir.isDirectory) {
                val targetScriptsDir = File(importDir, SCRIPTS_DIR)
                targetScriptsDir.mkdirs()
                val scriptFiles = scriptsDir.listFiles()
                for (i in scriptFiles.indices) {
                    val doc = scriptFiles[i]
                    if (doc.isFile) {
                        val content = context.contentResolver.openInputStream(doc.uri)
                            ?.bufferedReader()?.use { it.readText() } ?: continue
                        val safeName = sanitizeDirName(doc.name ?: "script")
                        File(targetScriptsDir, safeName).writeText(content)
                    }
                }
            }

            // Add to skills list and persist
            val newSkill = parsed.toBuilder()
                .setImportDirName(importDirName)
                .build()

            skillDataStore.updateData { current ->
                current.toBuilder().addSkills(newSkill).build()
            }

            debugLog(TAG, "Imported skill '${parsed.name}' from directory")
            Result.success(parsed.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import skill from directory", e)
            Result.failure(e)
        }
    }

    /**
     * Import a skill from a single SKILL.md file selected via SAF.
     * Creates a new skill directory with just the SKILL.md (no scripts).
     */
    suspend fun importSkillFromFile(fileUri: Uri): Result<String> {
        return try {
            val mdContent = context.contentResolver.openInputStream(fileUri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return Result.failure(Exception("Could not read file"))

            val parsed = parseSkillMd(mdContent, builtIn = false)
                ?: return Result.failure(Exception("Invalid $SKILL_MD format"))

            if (getSkillByName(parsed.name) != null) {
                return Result.failure(Exception("Skill '${parsed.name}' already exists"))
            }

            // Create import directory (sanitized name)
            val importDirName = sanitizeDirName(parsed.name)
            val importDir = File(context.filesDir, "$SKILLS_DIR/$importDirName")
            if (importDir.exists() && importDir.isDirectory) {
                val existingMd = File(importDir, SKILL_MD)
                if (existingMd.exists()) {
                    val existing = parseSkillMd(existingMd.readText(), false)
                    if (existing != null && existing.name != parsed.name) {
                        return Result.failure(IllegalArgumentException("Directory '$importDirName' already used by skill '${existing.name}'"))
                    }
                }
            }
            importDir.mkdirs()
            File(importDir, SKILL_MD).writeText(mdContent)

            val newSkill = parsed.toBuilder()
                .setImportDirName(importDirName)
                .build()

            skillDataStore.updateData { current ->
                current.toBuilder().addSkills(newSkill).build()
            }

            debugLog(TAG, "Imported skill '${parsed.name}' from file")
            Result.success(parsed.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import skill from file", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an imported skill (built-in skills cannot be deleted, only disabled).
     * Removes the skill's files from internal storage and from DataStore.
     */

    /**
     * Cancel the internal CoroutineScope. Call when the SkillManager is no longer needed
     * (e.g., during app shutdown) to prevent coroutine leaks.
     */
    fun close() {
        coroutineScope.cancel()
    }

    suspend fun deleteSkill(name: String): Result<Unit> {
        val skill = getSkillByName(name)
        if (skill == null) return Result.failure(Exception("Skill '$name' not found"))
        if (skill.builtIn) return Result.failure(Exception("Cannot delete built-in skill '$name'"))

        // Remove files
        val importDir = File(context.filesDir, "$SKILLS_DIR/${skill.importDirName}")
        if (importDir.exists()) importDir.deleteRecursively()

        // Remove from DataStore
        skillDataStore.updateData { current ->
            val builder = Skills.newBuilder()
            for (s in current.skillsList) {
                if (s.name != name) builder.addSkills(s)
            }
            builder.build()
        }

        debugLog(TAG, "Deleted imported skill '$name'")
        return Result.success(Unit)
    }
}
