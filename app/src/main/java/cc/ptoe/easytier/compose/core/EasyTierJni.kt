package cc.ptoe.easytier.compose.core

import com.easytier.jni.ConfigServerEventCallback
import com.easytier.jni.EasyTierJNI

/** App-owned facade over the bundled reference EasyTier JNI library. */
object EasyTierJni {
    fun setTunFd(instanceName: String, fd: Int): Int = EasyTierJNI.setTunFd(instanceName, fd)
    fun parseConfig(config: String): Int = EasyTierJNI.parseConfig(config)
    fun runNetworkInstance(config: String): Int = EasyTierJNI.runNetworkInstance(config)
    fun retainNetworkInstance(instanceNames: Array<String>?): Int = EasyTierJNI.retainNetworkInstance(instanceNames)
    fun collectNetworkInfos(maxLength: Int): String? = EasyTierJNI.collectNetworkInfos(maxLength)
    fun listInstances(maxLength: Int): String? = EasyTierJNI.listInstances(maxLength)

    /**
     * Starts the core's config-server client. The core connects, heartbeats and
     * applies the server's configs by running its own instances; [callback] is
     * invoked on a core thread, so it must only hand the event off.
     */
    fun startConfigServerClient(
        url: String,
        hostname: String?,
        machineId: String,
        secureMode: Boolean,
        callback: ConfigServerEventCallback,
    ): Int = EasyTierJNI.startConfigServerClient(url, hostname, machineId, secureMode, callback)

    fun stopConfigServerClient(): Int = EasyTierJNI.stopConfigServerClient()

    fun isConfigServerClientConnected(): Boolean = EasyTierJNI.isConfigServerClientConnected()

    /**
     * Calls an exposed EasyTier RPC method with protobuf JSON payloads. Used to
     * query instance-targeted read-only RPCs (e.g. VpnPortalRpc.GetVpnPortalInfo)
     * that collectNetworkInfos does not surface.
     */
    fun callJsonRpc(
        serviceName: String,
        methodName: String,
        domainName: String?,
        payloadJson: String,
    ): String? = EasyTierJNI.callJsonRpc(serviceName, methodName, domainName, payloadJson)

    fun getLastError(): String? = EasyTierJNI.getLastError()
}
