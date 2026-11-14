package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.EasyTierProfile
import java.net.URI
import kotlinx.coroutines.CancellationException

/** How long a successful sync stays fresh before the next start re-reads it. */
internal const val AUTO_REFRESH_INTERVAL_MILLIS = 30 * 60 * 1000L

/**
 * Hard budget for the automatic refresh. Connecting must not stall on a dead
 * link: the stored configuration is used as soon as this expires.
 */
internal const val AUTO_REFRESH_TIMEOUT_MILLIS = 5_000L

/**
 * Whether [profile]'s configuration should be re-read from its import URL
 * before the next start.
 *
 * Pure policy: [nowMillis] is the caller's clock (injectable in tests) and
 * [intervalMillis] is configurable so tests do not have to wait 30 minutes.
 * A profile with no [EasyTierProfile.sourceUrl] never refreshes; a profile
 * that was never synced ([EasyTierProfile.sourceSyncedAt] is null) does.
 */
internal fun shouldAutoRefresh(
    profile: EasyTierProfile,
    nowMillis: Long,
    intervalMillis: Long = AUTO_REFRESH_INTERVAL_MILLIS,
): Boolean {
    if (!profile.autoRefresh) return false
    if (profile.sourceUrl.isNullOrBlank()) return false
    val lastSynced = profile.sourceSyncedAt ?: return true
    return nowMillis - lastSynced >= intervalMillis
}

internal sealed interface AutoRefreshResult {
    data class Success(val profile: EasyTierProfile) : AutoRefreshResult

    /**
     * [host] is the only part of the import URL that is safe to log: the full
     * URL may carry secrets in its query string.
     */
    data class Failure(val host: String, val reason: String) : AutoRefreshResult
}

/**
 * Re-reads [profile]'s import URL and returns the profile to start with.
 *
 * [profile] itself is never modified: on any failure the caller keeps its
 * stored copy untouched and only the returned [AutoRefreshResult.Failure] is
 * logged. A successful import is merged with [withImportedConfig] (identity,
 * display name, TUN mode and device-local options survive) and stamped with
 * [nowMillis]. [validate] runs the same validation a normal start would; a
 * rejected configuration is treated as a failed refresh.
 */
internal fun autoRefreshProfile(
    profile: EasyTierProfile,
    nowMillis: Long,
    load: (String) -> ProfileLinkResult,
    validate: (EasyTierProfile) -> Boolean,
): AutoRefreshResult {
    val url = profile.sourceUrl?.trim().orEmpty()
    if (url.isEmpty()) return failure("", "no source URL")
    val host = sourceHost(url)
    val loaded = try {
        load(url)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // Exception class only: messages may quote the link or its payload.
        return failure(host, error.javaClass.simpleName)
    }
    return when (loaded) {
        is ProfileLinkResult.Failure -> failure(host, loaded.message)
        is ProfileLinkResult.Success -> {
            val candidate = profile.withImportedConfig(loaded.profile).copy(sourceSyncedAt = nowMillis)
            val valid = try {
                validate(candidate)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                false
            }
            if (valid) {
                AutoRefreshResult.Success(candidate)
            } else {
                failure(host, "imported configuration is invalid")
            }
        }
    }
}

/** Host only: import URLs may carry secrets in the query string. */
internal fun sourceHost(url: String): String =
    runCatching { URI(url).host }.getOrNull().orEmpty().ifEmpty { "unknown host" }

/**
 * Drops anything after a `?` and truncates: failure messages can quote the
 * import link, whose query string may contain network secrets.
 */
private fun failure(host: String, reason: String): AutoRefreshResult.Failure =
    AutoRefreshResult.Failure(host, reason.substringBefore('?').take(80))
