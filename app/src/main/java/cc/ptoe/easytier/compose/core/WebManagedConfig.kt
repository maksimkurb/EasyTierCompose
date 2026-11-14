package cc.ptoe.easytier.compose.core

import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.isWebManaged
import cc.ptoe.easytier.compose.data.mergeInto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/**
 * App-side glue for EasyTier's config-server ("web-managed") mode.
 *
 * The connection itself is owned by the core: `startConfigServerClient` connects,
 * reconnects (1 s retry), heartbeats and runs the network instances the server
 * pushes. This file only contains the pure parts the app adds on top of that:
 *
 *  * config-server endpoint validation that mirrors the core's
 *    `ConfigServerEndpoint::parse` (link-import validation must not depend on
 *    starting a client);
 *  * parsing of the core's `ConfigServerEventCallback` events;
 *  * reading the delivered configuration through
 *    `api.config.ConfigRpcService/GetConfig` (the only exposed RPC that returns
 *    the instance's TOML);
 *  * the offline cache: what to persist, what to replay, and the live-vs-cached
 *    decision. A failed sync never touches the cache.
 */

// --- Config-server endpoint validation -------------------------------------

internal enum class ConfigServerUrlProblem {
    MISSING,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    MISSING_TOKEN,
}

/**
 * Schemes accepted by the core's `ConfigServerEndpoint::parse` with the JNI
 * build's feature set: `IpScheme` (tcp, udp, ws, wss, wg, quic, faketcp) plus
 * `TunnelScheme` (unix, http, https, ring, txt, srv).
 */
private val CONFIG_SERVER_SCHEMES = setOf(
    "tcp", "udp", "ws", "wss", "wg", "quic", "faketcp",
    "unix", "http", "https", "ring", "txt", "srv",
)

/** Schemes that need a host; `unix` endpoints use a socket path instead. */
private val HOSTLESS_SCHEMES = setOf("unix")

/**
 * Returns the first problem with [raw], or null when it is a config-server
 * endpoint the core accepts.
 *
 * Mirrors `ConfigServerEndpoint::parse`: an absolute URL with a supported
 * scheme and a non-empty last path segment (the user token, percent-decoded by
 * the core). For `tcp/udp`-style endpoints the core strips the path and keeps
 * only the token; for `ws/wss` the path stays in the connect URL.
 */
internal fun configServerUrlProblem(raw: String?): ConfigServerUrlProblem? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return ConfigServerUrlProblem.MISSING
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return ConfigServerUrlProblem.MALFORMED
    }
    val scheme = uri.scheme?.lowercase() ?: return ConfigServerUrlProblem.MALFORMED
    if (uri.isOpaque) return ConfigServerUrlProblem.MALFORMED
    if (scheme !in CONFIG_SERVER_SCHEMES) return ConfigServerUrlProblem.UNSUPPORTED_SCHEME
    if (scheme !in HOSTLESS_SCHEMES && uri.host.isNullOrBlank()) return ConfigServerUrlProblem.MISSING_HOST
    if (uri.port > 65_535) return ConfigServerUrlProblem.MALFORMED
    // The core takes the last path segment as the token and rejects an empty one.
    val token = uri.rawPath.orEmpty().split('/').lastOrNull().orEmpty()
    if (token.isEmpty()) return ConfigServerUrlProblem.MISSING_TOKEN
    return null
}

/**
 * Host of a config-server URL, for display and logging.
 *
 * Only the host is returned: the token in the path identifies the user and must
 * never be logged or shown as part of a "server address" summary.
 */
internal fun configServerHost(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return ""
    return runCatching { URI(value).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown host"
}

@StringRes
internal fun configServerUrlMessage(problem: ConfigServerUrlProblem): Int = when (problem) {
    ConfigServerUrlProblem.MISSING -> R.string.error_config_server_url_required
    ConfigServerUrlProblem.MALFORMED, ConfigServerUrlProblem.MISSING_HOST ->
        R.string.error_config_server_url_invalid
    ConfigServerUrlProblem.UNSUPPORTED_SCHEME -> R.string.error_config_server_url_scheme
    ConfigServerUrlProblem.MISSING_TOKEN -> R.string.error_config_server_url_token
}

// --- Core events -----------------------------------------------------------

internal sealed interface ConfigServerEvent {
    /** The server applied a configuration; the core now runs [instanceName]. */
    data class Applied(
        val instanceId: String,
        val instanceName: String,
        val networkName: String,
    ) : ConfigServerEvent

    /** The server removed the instance (also emitted before a config update). */
    data class Removed(
        val instanceId: String,
        val instanceName: String,
        val networkName: String,
    ) : ConfigServerEvent

    /** Unknown or failed event, ignored. */
    data object Other : ConfigServerEvent
}

internal fun parseConfigServerEvent(eventJson: String): ConfigServerEvent {
    val obj = runCatching { Json.parseToJsonElement(eventJson).jsonObject }.getOrNull()
        ?: return ConfigServerEvent.Other
    if (obj["success"]?.jsonPrimitive?.booleanOrNull == false) return ConfigServerEvent.Other
    val instanceName = obj["instance_name"]?.jsonPrimitive?.content.orEmpty()
    if (instanceName.isEmpty()) return ConfigServerEvent.Other
    val instanceId = obj["instance_id"]?.jsonPrimitive?.content.orEmpty()
    val networkName = obj["network_name"]?.jsonPrimitive?.content.orEmpty()
    return when (obj["event"]?.jsonPrimitive?.content) {
        "run_network_instance" -> ConfigServerEvent.Applied(instanceId, instanceName, networkName)
        "delete_network_instance" -> ConfigServerEvent.Removed(instanceId, instanceName, networkName)
        else -> ConfigServerEvent.Other
    }
}

// --- Reading the delivered configuration -----------------------------------

/**
 * The core exposes the running instance's effective TOML through
 * `api.config.ConfigRpcService/GetConfig`; `collectNetworkInfos` only reports
 * runtime state (virtual IP, routes, peers), not the configuration.
 */
internal const val CONFIG_RPC_SERVICE = "api.config.ConfigRpcService"
internal const val CONFIG_RPC_METHOD = "get_config"

internal fun configRpcPayload(instanceName: String): String =
    """{"instance":{"instance_selector":{"name":${Json.encodeToString(instanceName)}}}}"""

/** Extracts `toml_config` from a GetConfig response, or null when absent. */
internal fun parseConfigRpcResponse(responseJson: String?): String? {
    if (responseJson.isNullOrBlank()) return null
    return runCatching {
        Json.parseToJsonElement(responseJson).jsonObject["toml_config"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Returns [rawToml] when it can be replayed as an ordinary local profile
 * (i.e. the app's importer can read it), otherwise null. Invalid or unreadable
 * server payloads must never replace a working cache.
 */
internal fun cacheableWebConfig(rawToml: String?): String? = webAppliedConfig(rawToml)?.toml

/** Device-relevant fields of a configuration delivered by the server. */
internal data class WebAppliedConfig(
    val toml: String,
    val enableMagicDns: Boolean,
    val mtu: Int,
)

/**
 * Parses a server-delivered TOML into the pieces the app needs: the TOML to
 * cache (verbatim) and the device-level options for the TUN. Returns null when
 * the payload cannot be replayed as a local configuration.
 */
internal fun webAppliedConfig(rawToml: String?): WebAppliedConfig? {
    val toml = rawToml?.takeIf { it.isNotBlank() } ?: return null
    val imported = when (val result = TomlProfileImporter.import(toml)) {
        is TomlImportResult.Success -> result.profile
        is TomlImportResult.Failure -> return null
    }
    return WebAppliedConfig(toml = toml, enableMagicDns = imported.enableMagicDns, mtu = imported.mtu)
}

/**
 * Profile after a sync attempt. [appliedToml] is the configuration the server
 * delivered (null/blank when the attempt failed): a failed sync keeps the
 * profile, its cache and its timestamp untouched.
 */
internal fun webProfileAfterSync(
    profile: EasyTierProfile,
    appliedToml: String?,
    syncedAt: Long,
): EasyTierProfile =
    if (appliedToml.isNullOrBlank()) profile else profile.copy(cachedToml = appliedToml, cachedAt = syncedAt)

// --- Live vs cached start decision -----------------------------------------

internal sealed interface WebStartSource {
    /** The server delivered a configuration; [instanceName] is the core's instance. */
    data class Live(val instanceName: String, val toml: String) : WebStartSource

    /** Start the cached configuration as an ordinary local instance. */
    data class Cached(val toml: String, val cachedAt: Long?) : WebStartSource

    /** The server delivered nothing and there is no cache to fall back to. */
    data object Unavailable : WebStartSource
}

/**
 * Decides what a web-managed Connect should start, given the outcome of the
 * bounded wait for the server's configuration.
 */
internal fun webStartSource(
    profile: EasyTierProfile,
    appliedToml: String?,
    instanceName: String?,
): WebStartSource {
    if (!appliedToml.isNullOrBlank() && !instanceName.isNullOrBlank()) {
        return WebStartSource.Live(instanceName, appliedToml)
    }
    val cached = profile.cachedToml?.takeIf { it.isNotBlank() }
    return if (cached != null) WebStartSource.Cached(cached, profile.cachedAt) else WebStartSource.Unavailable
}

// --- Cached configuration replay -------------------------------------------

/** Outcome of the editor's one-shot config-server sync. */
sealed interface WebSyncResult {
    data class Success(val toml: String, val instanceName: String, val syncedAt: Long) : WebSyncResult

    data class Failure(@param:StringRes val messageRes: Int) : WebSyncResult
}

internal sealed interface WebConfigReplay {
    data class Success(val profile: EasyTierProfile, val toml: String) : WebConfigReplay

    data class Failure(@param:StringRes val messageRes: Int) : WebConfigReplay
}

/**
 * Turns the cached server configuration into a normal, locally-runnable profile.
 *
 * The delivered TOML keeps the server's instance id/name; replaying it verbatim
 * would collide with the instance the config-server client may start in the
 * background. The replay therefore takes the profile's id as its instance name
 * (so the existing transports/pollers find it by `profile.id`) and the profile's
 * TUN mode, and keeps everything else from the server configuration.
 */
internal fun replayWebConfig(
    profile: EasyTierProfile,
    globalSettings: GlobalSettings = GlobalSettings(),
): WebConfigReplay {
    if (!profile.isWebManaged) return WebConfigReplay.Failure(R.string.error_web_no_cache)
    val cached = profile.cachedToml?.takeIf { it.isNotBlank() }
        ?: return WebConfigReplay.Failure(R.string.error_web_no_cache)
    val imported = when (val result = TomlProfileImporter.import(cached)) {
        is TomlImportResult.Success -> result.profile
        is TomlImportResult.Failure -> return WebConfigReplay.Failure(
            result.messageRes ?: R.string.error_web_cached_invalid,
        )
    }
    val replay = imported.copy(
        id = profile.id,
        name = profile.name,
        tunMode = profile.tunMode,
        hostname = profile.hostname?.trim()?.takeIf { it.isNotEmpty() } ?: imported.hostname,
    )
    val effective = globalSettings.mergeInto(replay)
    return WebConfigReplay.Success(effective, TomlConfigBuilder.build(effective, globalSettings))
}

/** Read-only summary of a cached server configuration, for the editor/dashboard. */
internal data class WebCachedSummary(
    val networkName: String,
    val virtualIpv4: String?,
    val dhcp: Boolean,
)

internal fun webCachedSummary(cachedToml: String?): WebCachedSummary? {
    val toml = cachedToml?.takeIf { it.isNotBlank() } ?: return null
    val imported = when (val result = TomlProfileImporter.import(toml)) {
        is TomlImportResult.Success -> result.profile
        is TomlImportResult.Failure -> return null
    }
    return WebCachedSummary(imported.networkName, imported.virtualIpv4, imported.dhcp)
}
