package dev.agentic.data.repo

import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.McpServerDef

/**
 * Global component configuration (skills, skill sources, plugins, MCP servers). Same contract
 * as [ProvidersRepository]: the ONLY path from features to this API surface.
 */
@Suppress("TooManyFunctions") // cohesive facade of one-line delegations over a single API area
class GlobalSettingsRepository(private val api: AgenticApi) {
    suspend fun globalSettings() = api.getGlobalSettings()
    suspend fun toggleComponent(kind: String, name: String, enabled: Boolean) =
        api.toggleGlobalComponent(kind, name, enabled)

    suspend fun skillCatalog(refresh: Boolean = false) = api.getSkillCatalog(refresh)
    suspend fun installSkill(source: String, update: Boolean = false) = api.installSkill(source, update)
    suspend fun deleteSkill(name: String) = api.deleteSkill(name)
    suspend fun skillSources() = api.getSkillSources()
    suspend fun addSkillSource(url: String) = api.addSkillSource(url)
    suspend fun deleteSkillSource(url: String) = api.deleteSkillSource(url)

    suspend fun installPlugin(name: String) = api.installPlugin(name)
    suspend fun uninstallPlugin(name: String) = api.uninstallPlugin(name)

    suspend fun addMcpServer(def: McpServerDef) = api.addMcpServer(def)
    suspend fun deleteMcpServer(name: String) = api.deleteMcpServer(name)
}
