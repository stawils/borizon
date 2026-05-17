package com.borizon.app.ai.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.borizon.app.skills.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.borizon.app.ai.tools.ToolCallTracker

/**
 * SkillTools — combined skill listing and execution.
 * Merged from SkillListTools + SkillLoadTools to reduce ToolSet registration overhead.
 */
class SkillTools(
    private val skillManager: SkillManager,
    private val jsBridge: JavascriptBridge,
    private val actionChannel: Channel<BorizonAction>,
) : ToolSet {

    companion object {
        private const val TAG = "SkillTools"
    }

    @Tool(description = "List available skills.")
    fun listSkills(): Map<String, String> {
        ToolCallTracker.increment()
        val skills = skillManager.getSelectedSkills()
        val skillList = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }

        actionChannel.trySend(BorizonAction.Progress(
            label = "${skills.size} skills available",
            isInProgress = false,
            toolType = ToolType.LIST_SKILLS,
        ))

        return mapOf("skills" to skillList, "count" to skills.size.toString())
    }

    @Tool(description = "Run a skill by name.")
    fun loadSkill(
        @ToolParam(description = "Skill name") skillName: String,
        @ToolParam(description = "User intent") userAction: String,
        @ToolParam(description = "JSON params, default {}") data: String = "{}",
    ): Map<String, String> {
        ToolCallTracker.increment()
        actionChannel.trySend(BorizonAction.Progress(
            label = "Loading \"$skillName\"",
            isInProgress = true,
            toolType = ToolType.LOAD_SKILL,
        ))

        val skill = skillManager.getSkillByName(skillName.trim())
        if (skill == null) {
            actionChannel.trySend(BorizonAction.Progress(
                label = "Skill not found",
                isInProgress = false,
                toolType = ToolType.LOAD_SKILL,
            ))
            return mapOf(
                "error" to "Skill '$skillName' not found",
                "available" to skillManager.getSelectedSkills().joinToString(", ") { it.name },
            )
        }

        val jsUrl = skillManager.getJsSkillUrl(skill.name, "index.html")
        if (jsUrl != null) {
            return executeJsSkill(skill, jsUrl, userAction, data)
        }

        actionChannel.trySend(BorizonAction.Progress(
            label = "Loaded \"${skill.name}\"",
            isInProgress = false,
            toolType = ToolType.LOAD_SKILL,
            detailDescription = skill.description,
        ))
        return mapOf(
            "skill_name" to skill.name,
            "instructions" to skill.instructions,
            "note" to "IMPORTANT: Execute these instructions NOW. Call the required tools immediately. Do NOT acknowledge or describe what you will do — just do it.",
        )
    }

    private fun executeJsSkill(
        skill: com.borizon.app.proto.Skill,
        url: String,
        userAction: String,
        data: String,
    ): Map<String, String> {
        return runBlocking(Dispatchers.IO) {
            actionChannel.trySend(BorizonAction.Progress(
                label = "Running \"${skill.name}\"",
                isInProgress = true,
                toolType = ToolType.RUN_JS,
                detailDescription = userAction,
            ))

            try {
                val rawResult = jsBridge.executeJs(url, data.ifBlank { "{}" })
                val json = try { JSONObject(rawResult) } catch (_: Exception) { null }

                val resultText = json?.optString("result") ?: rawResult
                val hasError = json != null && json.has("error") && !json.isNull("error")

                val webviewUrl = if (json != null && json.has("webview") && !json.isNull("webview")) {
                    val rawUrl = json.getJSONObject("webview").optString("url", "")
                    if (rawUrl.isNotBlank()) skillManager.getJsSkillWebviewUrl(skill.name, rawUrl)
                    else url
                } else url
                val aspectRatio = if (json != null && json.has("webview") && !json.isNull("webview")) {
                    json.getJSONObject("webview").optDouble("aspectRatio", 1.5).toFloat()
                } else 1.5f
                actionChannel.trySend(BorizonAction.Dashboard(
                    url = webviewUrl,
                    title = skill.name,
                    aspectRatio = aspectRatio,
                ))

                actionChannel.trySend(BorizonAction.Progress(
                    label = if (hasError) "Skill error" else "\"${skill.name}\" done",
                    isInProgress = false,
                    toolType = ToolType.RUN_JS,
                    detailDescription = resultText.take(80),
                ))

                if (hasError) {
                    mapOf("error" to (json?.getString("error") ?: "Skill failed"), "skill" to skill.name)
                } else {
                    mapOf(
                        "skill" to skill.name,
                        "result" to resultText,
                        "note" to "Summarize the result for the user.",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "JS skill failed", e)
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Skill failed",
                    isInProgress = false,
                    toolType = ToolType.RUN_JS,
                ))
                mapOf("error" to "Skill execution failed: ${e.message}", "skill" to skill.name)
            }
        }
    }
}
