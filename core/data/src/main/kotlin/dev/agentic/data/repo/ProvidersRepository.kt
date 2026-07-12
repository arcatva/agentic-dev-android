package dev.agentic.data.repo

import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.NativeOverrideReq
import dev.agentic.data.net.NewProviderReq

/**
 * BYOK provider + native-model-override management. Sole owner of the corresponding API
 * surface — feature ViewModels must come through here, never through [AgenticApi] directly
 * (enforced by the architecture test).
 */
class ProvidersRepository(private val api: AgenticApi) {
    suspend fun providers() = api.providers()
    suspend fun addProvider(req: NewProviderReq) = api.addProvider(req)
    suspend fun deleteProvider(name: String) = api.deleteProvider(name)

    // ChatGPT subscription OAuth (connect a ChatGPT plan instead of an API key).
    suspend fun chatgptLoginStart() = api.chatgptLoginStart()
    suspend fun chatgptLoginComplete(code: String, state: String) = api.chatgptLoginComplete(code, state)
    suspend fun chatgptStatus() = api.chatgptStatus()

    suspend fun nativeModels() = api.nativeModels()
    suspend fun putNativeOverride(family: String, req: NativeOverrideReq) = api.putNativeOverride(family, req)
    suspend fun deleteNativeOverride(family: String) = api.deleteNativeOverride(family)

    suspend fun getRouting() = api.getRouting()
    suspend fun setRouting(tradeoff: Float) = api.setRouting(tradeoff)
}
