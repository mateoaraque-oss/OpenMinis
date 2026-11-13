package com.openminis.app.localmodel

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import java.util.UUID

/**
 * [T-orion-local-model] Wires a running llama-server into the provider store.
 *
 * The whole point of the feature is "one tap": after the engine answers on
 * loopback, the app must already have a provider, a model entry, and that
 * entry selected, with no form to fill in. This object owns that wiring and is
 * idempotent, so tapping Start twice (or restarting on a different port)
 * updates the existing provider instead of piling up duplicates.
 *
 * The instance is an ordinary OpenAI-compatible provider pointing at
 * `http://127.0.0.1:<port>`, which means every existing feature — model
 * picker, groups, tool calls, subagents — works unchanged.
 */
object LocalModelProvider {

    const val LABEL = "Orion local"

    /**
     * llama-server ignores the key, but the credential store and the routing
     * layer both behave better with a non-empty value; the empty-key compat
     * path exists for user-entered endpoints, not for ours.
     */
    private const val API_KEY = "sk-orion-local"

    suspend fun ensureProvider(
        repo: ProviderRepository,
        port: Int,
        modelId: String,
        displayName: String,
        contextWindow: Int = 8192,
    ): ModelEntry? {
        // The initial config load is async. Reading repo.instances before it
        // finishes would see an empty list and create a duplicate provider on
        // the first tap after a cold start.
        repo.awaitConfigLoaded()

        val baseUrl = "http://127.0.0.1:$port"

        var instance = repo.instances.firstOrNull {
            it.label.equals(LABEL, ignoreCase = true)
        }
        if (instance == null) {
            instance = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = LABEL,
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = baseUrl,
                appendV1Suffix = true,
            )
            repo.addInstance(instance)
            repo.saveApiKey(instance.id, API_KEY)
        } else {
            if (instance.customBaseURL != baseUrl) {
                instance.customBaseURL = baseUrl
                repo.updateInstance(instance)
            }
            if (repo.loadApiKey(instance.id).isNullOrBlank()) {
                repo.saveApiKey(instance.id, API_KEY)
            }
        }

        var entry = repo.entriesFor(instance.id)
            .firstOrNull { it.baseModel.id == modelId && !it.isHidden }
        if (entry == null) {
            repo.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = LLMModel(
                        id = modelId,
                        displayName = displayName,
                        provider = LABEL,
                        contextWindow = contextWindow,
                        // [T-orion-local-chat] A phone model that writes more
                        // than ~1000 tokens per reply is unusable anyway, and
                        // the smaller cap leaves the rest of the window for
                        // the conversation instead of truncating the prompt.
                        maxOutputTokens = 1024,
                        supportsReasoning = false,
                    ),
                    // Custom so a later /v1/models refresh carries this entry
                    // forward instead of replacing it out from under a
                    // selected model.
                    isCustom = true,
                ),
            )
            // Re-read: the store canonicalises entry ids to
            // "{instanceId}/{modelId}" on save, so the id of the object we
            // just built is already stale and would never match
            // lastUsedVisibleEntry().
            entry = repo.entriesFor(instance.id)
                .firstOrNull { it.baseModel.id == modelId && !it.isHidden }
        }

        // [T-orion-local-chat] Keep the entry's window in step with the engine
        // that was actually launched. An entry created by an older build still
        // advertises the 4096 window, and the chat budgets every request
        // against that number, so it would keep overflow-guarding for a limit
        // the engine no longer has.
        if (entry != null && entry.baseModel.contextWindow != contextWindow) {
            repo.updateEntry(
                entry.copy(baseModel = entry.baseModel.copy(contextWindow = contextWindow)),
            )
            entry = repo.entriesFor(instance.id)
                .firstOrNull { it.baseModel.id == modelId && !it.isHidden }
        }

        // [T-orion-local-chat] Normalize the entry's provider field: catalog
        // sync rewrites it to "Custom", which breaks label-based detection.
        // Re-write it to LABEL so isLocalModelSelected keeps matching even if
        // the instance lookup path isn't taken.
        if (entry != null &&
            !entry.baseModel.provider.equals(LABEL, ignoreCase = true)) {
            repo.updateEntry(
                entry.copy(baseModel = entry.baseModel.copy(provider = LABEL)),
            )
            entry = repo.entriesFor(instance.id)
                .firstOrNull { it.baseModel.id == modelId && !it.isHidden }
        }

        // New chats fall back to the last-used entry; setting it here is what
        // makes the next message go to the phone instead of the cloud.
        if (entry != null) repo.lastUsedEntryId = entry.id

        // [T-orion-onboarding-local] First-run friendliness: with no model
        // group yet, put this entry in one. The landing's "Start a
        // conversation" unlocks on `hasGroups`, and without this the user
        // would be sent through the model picker after the model already
        // downloaded and started by itself.
        if (entry != null && repo.config.value.modelGroups.isEmpty()) {
            val group = ModelGroup(name = LABEL)
            group.memberEntryIds.add(entry.id)
            repo.addGroup(group)
            if (repo.config.value.defaultPrimaryGroupId == null) {
                repo.defaultPrimaryGroupId = group.id
            }
        }
        return entry
    }
}
