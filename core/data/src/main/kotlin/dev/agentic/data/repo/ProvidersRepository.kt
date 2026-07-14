package dev.agentic.data.repo

import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.NativeOverrideReq
import dev.agentic.data.net.NewProviderReq

/**
 * BYOK provider + native-model-override management. Sole owner of the corresponding API
 * surface — feature ViewModels must come through here, never through [AgenticApi] directly
 * (enforced by the architecture test).
 */
// Thin pass-through aggregator over one API surface; the function count is inherent, not a smell.
@Suppress("TooManyFunctions")
class ProvidersRepository(private val api: AgenticApi) {
    suspend fun providers() = api.providers()
    suspend fun addProvider(req: NewProviderReq) = api.addProvider(req)
    suspend fun deleteProvider(name: String) = api.deleteProvider(name)

    suspend fun nativeModels() = api.nativeModels()
    suspend fun putNativeOverride(family: String, req: NativeOverrideReq) = api.putNativeOverride(family, req)
    suspend fun deleteNativeOverride(family: String) = api.deleteNativeOverride(family)

    suspend fun getRouting() = api.getRouting()
    suspend fun setRouting(tradeoff: Float) = api.setRouting(tradeoff)

    suspend fun startSubscriptionLogin() = api.startSubscriptionLogin()
    suspend fun subscriptionStatus() = api.subscriptionStatus()
    suspend fun subscriptionLogout() = api.subscriptionLogout()
}
