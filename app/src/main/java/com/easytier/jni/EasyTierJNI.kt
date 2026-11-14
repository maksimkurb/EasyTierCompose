package com.easytier.jni

/**
 * Receives the config-server client's apply/delete events.
 *
 * The core invokes [onEvent] on its own worker thread; implementations must
 * return immediately and must not call back into the JNI facade (the FFI
 * rejects JNI calls made from a callback).
 */
fun interface ConfigServerEventCallback {
    fun onEvent(eventJson: String)
}

/**
 * JNI symbol owner retained for the reference libeasytier_android_jni binary.
 * App code uses cc.ptoe.easytier.compose.core.EasyTierJni instead.
 */
object EasyTierJNI {
    init {
        System.loadLibrary("easytier_android_jni")
    }

    @JvmStatic external fun setTunFd(instanceName: String, fd: Int): Int
    @JvmStatic external fun parseConfig(config: String): Int
    @JvmStatic external fun runNetworkInstance(config: String): Int
    @JvmStatic external fun retainNetworkInstance(instanceNames: Array<String>?): Int
    @JvmStatic external fun collectNetworkInfos(maxLength: Int): String?
    @JvmStatic external fun listInstances(maxLength: Int): String?

    /**
     * Starts the core's managed config-server client (singleton).
     *
     * The core owns connect/reconnect, heartbeat and applying the configs the
     * server pushes (it starts and stops network instances itself). Events are
     * delivered to [callback] as JSON. See the core's
     * `management/full/web_client.rs`.
     */
    @JvmStatic
    external fun startConfigServerClient(
        url: String,
        hostname: String?,
        machineId: String,
        secureMode: Boolean,
        callback: ConfigServerEventCallback?,
    ): Int

    /** Stops the config-server client and removes the instances it manages. */
    @JvmStatic external fun stopConfigServerClient(): Int

    /** Whether the config-server client is currently connected. */
    @JvmStatic external fun isConfigServerClientConnected(): Boolean

    @JvmStatic external fun callJsonRpc(
        serviceName: String,
        methodName: String,
        domainName: String?,
        payloadJson: String,
    ): String?
    @JvmStatic external fun getLastError(): String?
}
