package cc.ptoe.easytier.compose.core

import com.easytier.jni.ConfigServerEventCallback

/**
 * The native calls the config-server session needs.
 *
 * Extracted behind an interface so the session logic can be exercised with a
 * fake in unit tests; the real implementation is [JniWebConfigServerBridge].
 */
internal interface WebConfigServerBridge {
    /**
     * Starts the core's managed client. Returns the JNI result code; on failure
     * the JNI layer throws a RuntimeException whose message is the FFI error.
     */
    fun start(url: String, hostname: String?, machineId: String, onEvent: (String) -> Unit): Int

    /** Stops the client and the instances it manages. Idempotent. */
    fun stop(): Int

    fun isConnected(): Boolean

    /** Effective TOML of a running instance, via api.config.ConfigRpcService. */
    fun configToml(instanceName: String): String?

    /** Keeps only the named instances (used to drop the cached fallback instance). */
    fun retainOnly(instanceNames: Array<String>): Int
}

internal object JniWebConfigServerBridge : WebConfigServerBridge {
    override fun start(
        url: String,
        hostname: String?,
        machineId: String,
        onEvent: (String) -> Unit,
    ): Int {
        // The JNI layer keeps a global reference to this object, so it stays
        // alive for the whole client lifetime. onEvent only hands the JSON to a
        // channel: JNI calls are rejected from inside the callback.
        val callback = ConfigServerEventCallback { eventJson -> onEvent(eventJson) }
        return EasyTierJni.startConfigServerClient(
            url = url,
            hostname = hostname,
            machineId = machineId,
            secureMode = false,
            callback = callback,
        )
    }

    override fun stop(): Int = EasyTierJni.stopConfigServerClient()

    override fun isConnected(): Boolean = EasyTierJni.isConfigServerClientConnected()

    override fun configToml(instanceName: String): String? {
        val response = EasyTierJni.callJsonRpc(
            serviceName = CONFIG_RPC_SERVICE,
            methodName = CONFIG_RPC_METHOD,
            domainName = null,
            payloadJson = configRpcPayload(instanceName),
        )
        return parseConfigRpcResponse(response)
    }

    override fun retainOnly(instanceNames: Array<String>): Int = EasyTierJni.retainNetworkInstance(instanceNames)
}
