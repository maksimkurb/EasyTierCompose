package cc.ptoe.easytier.compose.core

import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.EasyTierProfile
import java.net.URI

/** Result of importing a profile from a URL (TOML or an EasyTier share link). */
internal sealed interface ProfileLinkResult {
    data class Success(val profile: EasyTierProfile) : ProfileLinkResult

    /**
     * [message] is an English, log-friendly description. [messageRes] is the
     * localized string the UI should show when present.
     */
    data class Failure(
        val message: String,
        @param:StringRes val messageRes: Int? = null,
    ) : ProfileLinkResult
}

/**
 * Imports a profile from a user-supplied URL.
 *
 * Two formats are accepted:
 *  - an EasyTier configuration share link (`https://easytier.cn/comp_cfg?...`),
 *    which is decoded entirely on-device and makes no network request;
 *  - a plain TOML configuration served over HTTP(S), fetched with
 *    [fetchConfigText] (explicit, user-triggered only).
 *
 * Web console *config-server* URLs (`udp://host:22020/<user>`) are rejected
 * with a dedicated message: they are a runtime management channel, not a
 * downloadable configuration.
 */
internal object ProfileLinkSource {
    private val HTTP_SCHEMES = setOf("http", "https")
    private val CONFIG_SERVER_SCHEMES = setOf("udp", "tcp", "ws", "wss", "ring")

    internal fun load(
        input: String,
        fetch: (URI) -> ConfigText = ::fetchConfigText,
    ): ProfileLinkResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ProfileLinkResult.Failure("Empty link", R.string.profiles_import_invalid_url)
        }

        when (val share = EasyTierWebLink.parse(trimmed)) {
            is WebLinkResult.Success -> return ProfileLinkResult.Success(
                share.profile.copy(sourceUrl = trimmed),
            )

            is WebLinkResult.Failure -> return ProfileLinkResult.Failure(share.message, share.messageRes)
            WebLinkResult.NotShareLink -> Unit
        }

        val uri = try {
            URI(trimmed)
        } catch (error: Exception) {
            return ProfileLinkResult.Failure(
                "Invalid URL: ${error.message}",
                R.string.profiles_import_invalid_url,
            )
        }
        val scheme = uri.scheme?.lowercase()
        if (uri.host.isNullOrEmpty() || scheme !in HTTP_SCHEMES) {
            return if (scheme in CONFIG_SERVER_SCHEMES) {
                ProfileLinkResult.Failure(
                    "Config-server URLs cannot be imported",
                    R.string.profiles_import_web_console_url,
                )
            } else {
                ProfileLinkResult.Failure("Invalid URL", R.string.profiles_import_invalid_url)
            }
        }

        val config = try {
            fetch(uri)
        } catch (error: Exception) {
            return ProfileLinkResult.Failure(
                error.message ?: "Failed to read the configuration",
                R.string.profiles_import_read_failed,
            )
        }
        if (config.tooLarge) {
            return ProfileLinkResult.Failure("Configuration is too large", R.string.profiles_import_too_large)
        }

        return when (val imported = TomlProfileImporter.import(config.text)) {
            is TomlImportResult.Success -> ProfileLinkResult.Success(
                imported.profile.copy(sourceUrl = trimmed),
            )

            is TomlImportResult.Failure -> ProfileLinkResult.Failure(imported.message, imported.messageRes)
        }
    }
}

/**
 * Replaces the network configuration of this profile with [imported] while
 * keeping app/device-local choices that the remote source must not silently
 * change: the profile identity, its display name, TUN mode and engine options.
 */
fun EasyTierProfile.withImportedConfig(imported: EasyTierProfile): EasyTierProfile = imported.copy(
    id = id,
    name = name,
    tunMode = tunMode,
    tunDeviceName = tunDeviceName,
    startOnBoot = startOnBoot,
    mtu = mtu,
    multiThread = multiThread,
    multiThreadCount = multiThreadCount,
    foreignRelayBpsLimit = foreignRelayBpsLimit,
    instanceRecvBpsLimit = instanceRecvBpsLimit,
    socketMark = socketMark,
    sourceUrl = sourceUrl,
)
