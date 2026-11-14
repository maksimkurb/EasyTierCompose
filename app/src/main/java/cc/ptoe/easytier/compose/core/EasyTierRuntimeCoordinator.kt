package cc.ptoe.easytier.compose.core
import androidx.annotation.StringRes

import android.content.Context
import android.util.Log
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.WebConfigOrigin
import cc.ptoe.easytier.compose.data.WebServerState
import cc.ptoe.easytier.compose.data.WebSessionStatus
import cc.ptoe.easytier.compose.data.isWebManaged
import cc.ptoe.easytier.compose.data.mergeInto
import cc.ptoe.easytier.compose.transport.root.RootTunTransport
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import cc.ptoe.easytier.compose.transport.vpn.VpnTunTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Matches the TOML builder's default MTU, used until a delivered config arrives. */
private const val DEFAULT_MTU = 1380

class EasyTierRuntimeCoordinator private constructor(
    private val context: Context,
    permissionRequester: VpnPermissionRequester,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val vpn = VpnTunTransport(context, permissionRequester)
    private val root = RootTunTransport(context)
    // Shares the process-wide DataStore with the ViewModel/boot receiver; used
    // to persist a profile refreshed from its import URL before a start.
    private val profileRepository = ProfileRepository(context)
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null
    private val webBridge: WebConfigServerBridge = JniWebConfigServerBridge
    private var webSession: WebSession? = null

    /**
     * Swaps the VPN permission requester. BootCompletedReceiver binds a no-op requester
     * (background context can't launch the consent dialog); when MainActivity comes up it
     * swaps in the real activity-backed requester so a foreground connect() can prompt.
     */
    fun updatePermissionRequester(requester: VpnPermissionRequester) {
        vpn.updatePermissionRequester(requester)
    }

    companion object {
        private const val TAG = "EasyTierCoordinator"

        /** Upper bound for a whole pre-connect refresh, including validation. */
        private const val AUTO_REFRESH_BUDGET_MILLIS = 12_000L

        /**
         * How long Connect waits for the config server to deliver a configuration
         * before falling back to the cached one. The core keeps connecting in the
         * background afterwards; a configuration that arrives later is applied.
         */
        private const val WEB_FIRST_CONFIG_TIMEOUT_MILLIS = 12_000L

        // Process-wide singleton: BootCompletedReceiver and MainActivity must share one
        // coordinator. Two instances would each run their own poll loops and transports
        // against the same global native instance / root daemon, so a stop() from the
        // activity would be overridden by the boot receiver's still-polling loop, leaving
        // the VPN service running with no way to disconnect it.
        @Volatile private var instance: EasyTierRuntimeCoordinator? = null

        @Synchronized
        fun getInstance(context: Context, permissionRequester: VpnPermissionRequester): EasyTierRuntimeCoordinator {
            val current = instance
            if (current != null) {
                // Adopt the caller's requester so a boot-created singleton can prompt
                // once the activity is foregrounded.
                current.updatePermissionRequester(permissionRequester)
                return current
            }
            return EasyTierRuntimeCoordinator(context.applicationContext, permissionRequester).also { instance = it }
        }
    }

    /**
     * Starts [profile], which is also the single start path used by the
     * ViewModel's Connect action and by [cc.ptoe.easytier.compose.boot.BootCompletedReceiver].
     *
     * A profile imported from a URL is first re-read when its stored copy is
     * stale ([shouldAutoRefresh]); that happens outside the mutex so a dead or
     * slow source cannot delay stop()/disconnect(). Any refresh failure is
     * silent and the stored configuration is started as-is.
     */
    suspend fun start(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): RuntimeStatus {
        val refreshed = autoRefreshBeforeStart(profile, globalSettings)
        return mutex.withLock { startLocked(refreshed, globalSettings) }
    }

    private suspend fun startLocked(profile: EasyTierProfile, globalSettings: GlobalSettings): RuntimeStatus {
        if (profile.isWebManaged) return startWebManagedLocked(profile, globalSettings)
        // Auto-fill hostname from the Android device name when the profile leaves it blank,
        // so peers see a recognizable identity without forcing the user to configure it.
        // Global overrides are merged on top afterwards; build/validate merge again
        // internally, which is idempotent.
        val effectiveProfile = globalSettings.mergeInto(profile.withDeviceHostnameIfBlank(context))
        val errors = ProfileValidator().validate(effectiveProfile, globalSettings)
        if (errors.isNotEmpty()) return failure(effectiveProfile, errors.values.first().resolve(context))
        val toml = TomlConfigBuilder.build(effectiveProfile, globalSettings)
        stopLocked()
        activeProfile = effectiveProfile
        publish(RuntimeStatus(RuntimeState.STARTING, effectiveProfile.id, effectiveProfile.tunMode, null, null, null))
        return when (effectiveProfile.tunMode) {
            // No TUN mode: run the EasyTier core with no TUN device at all, in
            // the app process. VpnService and RootTun are both skipped.
            TunMode.NO_TUN -> startNoTun(effectiveProfile, toml)
            TunMode.VPN_SERVICE -> startVpn(effectiveProfile, toml, globalSettings)
            TunMode.ROOT_TUN -> startRoot(effectiveProfile, toml, globalSettings)
        }
    }

    // --- Config-server (web-managed) sessions ------------------------------
    //
    // The core's config-server client owns connecting, reconnecting (1 s retry),
    // the heartbeat and applying the server's configs: it starts and stops the
    // network instances itself. The app only (1) waits a bounded time for the
    // first applied instance, (2) reads its effective TOML through
    // api.config.ConfigRpcService to keep an offline cache, (3) attaches the TUN
    // to that instance through the regular VpnService transport, and (4) falls
    // back to the cached TOML as a normal local instance when the server is
    // unreachable. The client keeps retrying while the session runs; a
    // configuration that arrives later promotes the cached instance to live.

    /** Per-connection state of a web-managed session. */
    private class WebSession(
        val profileId: String,
        /** One-shot sync sessions must not write the cache themselves. */
        val persistCache: Boolean = true,
    ) {
        /** JSON events as delivered by the core callback thread. */
        val rawEvents = Channel<String>(Channel.UNLIMITED)

        /** Completed once the first configuration was captured and cached. */
        val firstApplied = CompletableDeferred<Unit>()

        @Volatile var active = true
        @Volatile var clientStarted = false
        @Volatile var clientFailed = false
        @Volatile var primaryInstance: String? = null
        @Volatile var appliedToml: String? = null
        @Volatile var appliedAt: Long? = null
        @Volatile var lastSyncedAt: Long? = null
        @Volatile var usingCache = false

        /** A cached fallback instance must be removed once the live TUN is up. */
        @Volatile var needsLocalRemoval = false
        @Volatile var primaryRemoved = false
        @Volatile var extraNetworks = 0
        @Volatile var configVersion = 0
        @Volatile var primaryDns = false
        @Volatile var primaryMtu = DEFAULT_MTU
        var eventsJob: Job? = null
        val extraNames = mutableSetOf<String>()

        fun toStatus(connected: Boolean): WebSessionStatus = WebSessionStatus(
            serverState = when {
                connected -> WebServerState.CONNECTED
                clientFailed -> WebServerState.DISCONNECTED
                clientStarted -> WebServerState.DISCONNECTED
                else -> WebServerState.CONNECTING
            },
            origin = when {
                usingCache -> WebConfigOrigin.CACHED
                appliedToml != null -> WebConfigOrigin.LIVE
                else -> null
            },
            lastSyncedAt = lastSyncedAt,
            extraNetworks = extraNetworks,
        )
    }

    private suspend fun startWebManagedLocked(profile: EasyTierProfile, globalSettings: GlobalSettings): RuntimeStatus {
        val effective = globalSettings.mergeInto(profile.withDeviceHostnameIfBlank(context))
        configServerUrlProblem(effective.configServerUrl)?.let { problem ->
            return failure(effective, context.getString(configServerUrlMessage(problem)))
        }
        // The core runs the config-server client in the app process; ROOT_TUN
        // runs EasyTier in the root daemon, which the client cannot manage.
        if (effective.tunMode == TunMode.ROOT_TUN) {
            return failure(effective, context.getString(R.string.error_web_managed_root_unsupported))
        }
        stopLocked()
        if (effective.tunMode == TunMode.VPN_SERVICE) {
            vpn.preparePermission(effective)?.let { return it }
        }
        activeProfile = effective
        val session = WebSession(effective.id).also {
            it.lastSyncedAt = effective.cachedAt
            webSession = it
        }
        publish(RuntimeStatus(RuntimeState.STARTING, effective.id, effective.tunMode, null, null, null))
        startWebEvents(session)
        val clientReady = startWebClient(session, effective)
        if (!clientReady) {
            session.clientFailed = true
            // A client that cannot start can never retry, so without a cache the
            // session cannot recover.
            if (effective.cachedToml.isNullOrBlank()) {
                stopWebSessionLocked(session)
                return failure(effective, context.getString(R.string.error_web_start_failed))
            }
        }
        publish(mutableStatus.value)

        val received = clientReady &&
            withTimeoutOrNull(WEB_FIRST_CONFIG_TIMEOUT_MILLIS) { session.firstApplied.await() } != null
        val appliedToml = if (received) session.appliedToml else null
        val instanceName = if (received) session.primaryInstance else null
        when (val source = webStartSource(effective, appliedToml, instanceName)) {
            is WebStartSource.Live -> {
                session.usingCache = false
                session.lastSyncedAt = session.appliedAt ?: session.lastSyncedAt
                if (effective.tunMode == TunMode.VPN_SERVICE) {
                    vpn.startManaged(
                        profile = effective,
                        instanceName = source.instanceName,
                        enableMagicDns = session.primaryDns,
                        mtu = session.primaryMtu,
                    )
                } else {
                    // No TUN to attach: the core-managed instance is the session.
                    publish(RuntimeStatus(RuntimeState.RUNNING, effective.id, effective.tunMode, null, null, null))
                }
                publish(mutableStatus.value)
                pollWebLive(session, effective)
                return mutableStatus.value
            }

            is WebStartSource.Cached -> {
                session.usingCache = true
                session.lastSyncedAt = source.cachedAt
                // The client keeps retrying in the background; if the server
                // delivers a configuration later, promoteWebSession() switches
                // the session over without a user action.
                return startCachedWebLocked(session, effective, globalSettings)
            }

            WebStartSource.Unavailable -> {
                stopWebSessionLocked(session)
                return failure(effective, context.getString(R.string.error_web_no_cache))
            }
        }
    }

    /**
     * Starts the cached server configuration as an ordinary local instance. The
     * cache is replayed with the profile id as the instance name and the
     * profile's TUN mode, so the regular transports/pollers apply unchanged.
     */
    private suspend fun startCachedWebLocked(
        session: WebSession,
        profile: EasyTierProfile,
        globalSettings: GlobalSettings,
    ): RuntimeStatus {
        val replay = when (val result = replayWebConfig(profile, globalSettings)) {
            is WebConfigReplay.Success -> result
            is WebConfigReplay.Failure -> {
                stopWebSessionLocked(session)
                return failure(profile, context.getString(result.messageRes))
            }
        }
        val errors = ProfileValidator().validate(replay.profile, globalSettings)
        if (errors.isNotEmpty()) {
            stopWebSessionLocked(session)
            return failure(profile, errors.values.first().resolve(context))
        }
        return when (profile.tunMode) {
            TunMode.NO_TUN -> startNoTun(replay.profile, replay.toml, clearInstances = false)
            TunMode.VPN_SERVICE -> startVpn(replay.profile, replay.toml, globalSettings, clearInstances = false)
            TunMode.ROOT_TUN -> failure(profile, context.getString(R.string.error_web_managed_root_unsupported))
        }
    }

    /** Starts the core's client for [session]. Returns false when the JNI call failed. */
    private suspend fun startWebClient(session: WebSession, profile: EasyTierProfile): Boolean {
        val machineId = profileRepository.machineId()
        val started = runCatching {
            webBridge.start(
                url = profile.configServerUrl.orEmpty(),
                hostname = profile.hostname,
                machineId = machineId,
            ) { json ->
                // Core thread: hand off and return; JNI calls are rejected here.
                session.rawEvents.trySend(json)
            }
        }
        if (started.isFailure || started.getOrDefault(-1) != 0) {
            Log.w(TAG, "config-server start failed: ${started.exceptionOrNull()?.javaClass?.simpleName ?: "result"}")
            return false
        }
        session.clientStarted = true
        return true
    }

    /**
     * One-shot config-server sync for the editor's "Sync now" action.
     *
     * Refused while a session is running; otherwise starts the core client,
     * waits (bounded) for the first applied configuration, stops the client
     * again and returns the TOML. Nothing is persisted here: the caller decides
     * whether the profile it belongs to is already stored.
     */
    suspend fun syncWebManagedConfig(profile: EasyTierProfile): WebSyncResult = mutex.withLock {
        if (activeProfile != null) return@withLock WebSyncResult.Failure(R.string.error_web_sync_while_running)
        val effective = profile.withDeviceHostnameIfBlank(context)
        configServerUrlProblem(effective.configServerUrl)?.let { problem ->
            return@withLock WebSyncResult.Failure(configServerUrlMessage(problem))
        }
        val session = WebSession(effective.id, persistCache = false)
        startWebEvents(session)
        if (!startWebClient(session, effective)) {
            stopWebSessionLocked(session)
            return@withLock WebSyncResult.Failure(R.string.error_web_start_failed)
        }
        val received = withTimeoutOrNull(WEB_FIRST_CONFIG_TIMEOUT_MILLIS) { session.firstApplied.await() } != null
        val toml = if (received) session.appliedToml else null
        val instance = session.primaryInstance
        val syncedAt = session.appliedAt
        stopWebSessionLocked(session)
        if (toml.isNullOrBlank() || instance.isNullOrBlank() || syncedAt == null) {
            WebSyncResult.Failure(R.string.error_web_sync_unreachable)
        } else {
            WebSyncResult.Success(toml, instance, syncedAt)
        }
    }

    /** Collects the core's config-server events for one session. */
    private fun startWebEvents(session: WebSession) {
        session.eventsJob = scope.launch {
            for (json in session.rawEvents) {
                if (!session.active) break
                when (val event = parseConfigServerEvent(json)) {
                    is ConfigServerEvent.Applied -> {
                        val applied = withContext(Dispatchers.IO) {
                            webAppliedConfig(runCatching { webBridge.configToml(event.instanceName) }.getOrNull())
                        }
                        if (applied == null) {
                            Log.w(TAG, "config-server configuration for ${event.instanceName} is unavailable")
                            continue
                        }
                        val now = System.currentTimeMillis()
                        if (session.persistCache) persistWebCache(session.profileId, applied.toml, now)
                        val isPrimary = session.primaryInstance.let { it == null || it == event.instanceName }
                        if (isPrimary) {
                            session.primaryInstance = event.instanceName
                            session.appliedToml = applied.toml
                            session.appliedAt = now
                            session.lastSyncedAt = now
                            session.primaryDns = applied.enableMagicDns
                            session.primaryMtu = applied.mtu
                            session.primaryRemoved = false
                            session.configVersion++
                            session.firstApplied.complete(Unit)
                            if (session.usingCache) promoteWebSession(session)
                        } else if (session.extraNames.add(event.instanceName)) {
                            session.extraNetworks = session.extraNames.size
                        }
                    }

                    is ConfigServerEvent.Removed -> {
                        // Also emitted right before an updated config is applied;
                        // a later Applied event for the same instance clears it.
                        if (event.instanceName == session.primaryInstance) session.primaryRemoved = true
                    }

                    ConfigServerEvent.Other -> Unit
                }
            }
        }
    }

    /** Switches a cached session to the configuration the server just delivered. */
    private suspend fun promoteWebSession(session: WebSession) = mutex.withLock {
        if (webSession !== session) return@withLock
        val profile = activeProfile ?: return@withLock
        if (!session.usingCache) return@withLock
        val instance = session.primaryInstance ?: return@withLock
        if (profile.tunMode == TunMode.VPN_SERVICE) {
            vpn.startManaged(profile, instance, session.primaryDns, session.primaryMtu)
            session.needsLocalRemoval = true
        } else {
            // No TUN to move: drop the cached fallback instance immediately.
            runCatching { webBridge.retainOnly(arrayOf(instance)) }
        }
        session.usingCache = false
        session.lastSyncedAt = session.appliedAt ?: session.lastSyncedAt
        pollJob?.cancelAndJoin()
        pollWebLive(session, profile)
        publish(mutableStatus.value)
        Log.i(TAG, "config-server session switched to the live configuration")
    }

    /**
     * Polls the core-managed instance and attaches the TUN to it. Peers, hostname
     * and NAT type come from collectNetworkInfos exactly like a local instance.
     */
    private fun pollWebLive(session: WebSession, profile: EasyTierProfile) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val exitDetector = CoreExitDetector()
            var attachedVersion = -1
            var removedFallback = false
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                val instance = session.primaryInstance
                if (instance == null) {
                    delay(1_000)
                    continue
                }
                val info = runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(instance) }
                    .onFailure { Log.w(TAG, "collectNetworkInfos failed: ${it.message}") }
                    .getOrNull()
                if (exitDetector.onPoll(info)) {
                    Log.w(TAG, "config-server instance is gone")
                    failure(
                        profile,
                        context.getString(
                            if (session.primaryRemoved) R.string.error_web_network_removed else R.string.error_core_stopped,
                        ),
                    )
                    vpn.stop()
                    runCatching { webBridge.stop() }
                    return@launch
                }
                if (info != null) {
                    if (!info.error.isNullOrBlank()) {
                        failure(profile, info.error)
                        vpn.stop()
                        runCatching { webBridge.stop() }
                        return@launch
                    }
                    if (!info.virtualIpv4.isNullOrBlank() && profile.tunMode == TunMode.VPN_SERVICE) {
                        if (attachedVersion != session.configVersion) {
                            // A re-applied config replaced the core instance; its old fd is gone.
                            vpn.resetEstablishState()
                            attachedVersion = session.configVersion
                        }
                        val status = vpn.establishWhenResolved(profile, info.virtualIpv4, info.routes)
                        publish(status.copy(peers = info.peers, hostname = info.hostname, natType = info.natType))
                        if (session.needsLocalRemoval && !removedFallback && status.state == RuntimeState.RUNNING) {
                            // Keep only the managed instance; the cached fallback is no longer needed.
                            runCatching { webBridge.retainOnly(arrayOf(instance)) }
                            removedFallback = true
                            session.needsLocalRemoval = false
                        }
                    } else {
                        publish(
                            mutableStatus.value.copy(
                                virtualIpv4 = info.virtualIpv4 ?: mutableStatus.value.virtualIpv4,
                                peers = info.peers,
                                hostname = info.hostname,
                                natType = info.natType,
                            ),
                        )
                    }
                }
                // STARTING polls faster to resolve the virtual IPv4 quickly.
                delay(if (mutableStatus.value.state == RuntimeState.RUNNING) 5_000 else 2_000)
            }
        }
    }

    private suspend fun stopWebSessionLocked(session: WebSession) {
        session.active = false
        session.eventsJob?.cancelAndJoin()
        session.eventsJob = null
        session.rawEvents.close()
        session.clientStarted = false
        runCatching { webBridge.stop() }
        if (webSession === session) webSession = null
    }

    /** Persists a successful sync; the cache is only ever written on success. */
    private suspend fun persistWebCache(profileId: String, toml: String, syncedAt: Long) {
        runCatching { profileRepository.saveWebCache(profileId, toml, syncedAt) }
            .onFailure { Log.w(TAG, "could not store the config-server cache: ${it.javaClass.simpleName}") }
    }

    /**
     * Publishes a status decorated with the web-session fields. The core's
     * connected flag is polled here (cheap JNI call) so the dashboard reflects
     * reconnects without an extra timer.
     */
    private fun publish(status: RuntimeStatus) {
        val session = webSession
        mutableStatus.value = if (session != null && session.profileId == status.profileId) {
            status.copy(web = session.toStatus(webBridge.isConnected()))
        } else {
            status
        }
    }

    /**
     * Re-reads a stale URL-backed profile right before it is started and stores
     * the result. Returns [profile] unchanged on every failure (offline, HTTP
     * or parse error, config-server URL, invalid configuration), so a broken
     * source can never overwrite the stored configuration.
     */
    private suspend fun autoRefreshBeforeStart(profile: EasyTierProfile, globalSettings: GlobalSettings): EasyTierProfile {
        if (!shouldAutoRefresh(profile, System.currentTimeMillis())) return profile
        val now = System.currentTimeMillis()
        // The budget only covers a hung DNS/parse; the HTTP timeouts below are
        // what normally bound a dead link. It is generous enough that a slow
        // but successful fetch is still applied.
        val result = withTimeoutOrNull(AUTO_REFRESH_BUDGET_MILLIS) {
            withContext(Dispatchers.IO) {
                autoRefreshProfile(
                    profile = profile,
                    nowMillis = now,
                    load = { url ->
                        ProfileLinkSource.load(url, fetch = { uri ->
                            fetchConfigText(uri, AUTO_REFRESH_TIMEOUT_MILLIS.toInt())
                        })
                    },
                    // Same validation a normal start performs, on the effective
                    // profile (global overrides + hostname auto-fill).
                    validate = { candidate ->
                        ProfileValidator()
                            .validate(globalSettings.mergeInto(candidate.withDeviceHostnameIfBlank(context)), globalSettings)
                            .isEmpty()
                    },
                )
            }
        }
        return when (result) {
            null -> {
                Log.w(TAG, "auto-refresh timed out for ${sourceHost(profile.sourceUrl.orEmpty())}")
                profile
            }

            is AutoRefreshResult.Failure -> {
                Log.w(TAG, "auto-refresh failed for ${result.host}: ${result.reason}")
                profile
            }

            is AutoRefreshResult.Success -> {
                val refreshed = result.profile
                // A successful sync always moves sourceSyncedAt forward.
                if (refreshed != profile) {
                    runCatching { profileRepository.save(refreshed) }
                        .onFailure { Log.w(TAG, "could not store refreshed profile: ${it.javaClass.simpleName}") }
                }
                Log.i(TAG, "auto-refreshed profile from ${sourceHost(profile.sourceUrl.orEmpty())}")
                refreshed
            }
        }
    }

    /**
     * Launches [start] in the coordinator's own [scope] (SupervisorJob + Dispatchers.IO),
     * decoupled from the caller's coroutine context. The returned [Job] can be awaited
     * for completion but cancelling it does NOT abort the start operation — the
     * SupervisorJob keeps [start] running until it settles on its own.
     *
     * Used by [BootCompletedReceiver] so its 8s `withTimeoutOrNull` wait only abandons
     * *waiting* for the result, not the start operation itself. This ensures `bind()`
     * and `remote.start(...)` complete even when boot-time root process startup is slow.
     */
    fun startDetached(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): Job =
        scope.launch { start(profile, globalSettings) }

    suspend fun stop(): RuntimeStatus = mutex.withLock { stopLocked() }

    /**
     * Attempts to reconnect to a root daemon left running by a previous app process
     * (orphan process). If the daemon reports a running EasyTier instance, matches
     * its [profileId] against [profileLookup] and, on a match, takes over: sets the
     * active profile, publishes the daemon's status, and resumes polling. If the
     * profile no longer exists, stops the orphan so it doesn't linger.
     *
     * Safe to call on every app launch: it is a no-op when no daemon is running
     * (state STOPPED) and when the active transport is VPN_SERVICE.
     */
    suspend fun attachOrphanRoot(profileLookup: suspend (String) -> EasyTierProfile?) = mutex.withLock {
        // Skip if a session is already active in this process.
        if (activeProfile != null) return@withLock
        val rootStatus = root.attach() ?: return@withLock
        val profileId = rootStatus.profileId
        val profile = if (profileId != null) profileLookup(profileId) else null
        if (profile == null) {
            // Profile was deleted while the app was gone — stop the orphan.
            root.stop()
            return@withLock
        }
        activeProfile = profile
        val status = root.adopt(rootStatus, profile)
        publish(status)
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollRoot()
    }

    /**
     * Attempts to adopt an EasyTier instance already running in-process (started by
     * BootCompletedReceiver in VPN_SERVICE or no_tun mode). Detects a running instance
     * via collectNetworkInfos, matches its profileId via [profileLookup], and resumes
     * polling without re-establishing the TUN/VPN interface.
     *
     * Safe to call on every app launch: it is a no-op when no in-process instance is
     * running (collectNetworkInfos returns null/empty) and when the active transport
     * is ROOT_TUN (handled by [attachOrphanRoot] instead, since the EasyTier core in
     * ROOT_TUN mode lives in the root daemon process, not in the app process).
     */
    suspend fun attachRunningInstance(
        globalSettings: GlobalSettings,
        profileLookup: suspend (String) -> EasyTierProfile?,
    ) = mutex.withLock {
        if (activeProfile != null) return@withLock
        val raw = runCatching { EasyTierJni.collectNetworkInfos(1) }.getOrNull() ?: return@withLock
        val map = runCatching {
            Json.parseToJsonElement(raw).jsonObject["map"]?.jsonObject
        }.getOrNull() ?: return@withLock
        if (map.isEmpty()) return@withLock
        // Find the first running instance without an error.
        val entry = map.entries.firstOrNull { (_, value) ->
            val obj = value.jsonObject
            obj["error_msg"]?.jsonPrimitive?.content.isNullOrBlank()
        } ?: return@withLock
        val profileId = entry.key
        val profile = profileLookup(profileId) ?: run {
            // Instance running but profile deleted — release it so it doesn't linger.
            EasyTierJni.retainNetworkInstance(null)
            return@withLock
        }
        activeProfile = profile
        if (profile.isWebManaged) {
            // A web-managed instance that survived in this process runs the cached
            // configuration; the config-server client itself is per-process.
            webSession = WebSession(profile.id).apply {
                usingCache = profile.cachedToml != null
                lastSyncedAt = profile.cachedAt
                appliedToml = profile.cachedToml
            }
        }
        val info = raw.networkInfo(profileId)
        when (profile.tunMode) {
            // In-process no_tun adoption only applies to NO_TUN sessions.
            TunMode.NO_TUN -> {
                publish(
                    RuntimeStatus(
                        state = RuntimeState.RUNNING,
                        profileId = profile.id,
                        tunMode = profile.tunMode,
                        virtualIpv4 = info?.virtualIpv4,
                        tunDevice = null,
                        error = null,
                        peers = info?.peers.orEmpty(),
                        hostname = info?.hostname,
                        natType = info?.natType,
                    ),
                )
                pollNoTun()
            }
            TunMode.VPN_SERVICE -> {
                val status = vpn.adopt(profile, info?.virtualIpv4, info?.routes.orEmpty())
                publish(status)
                if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollVpn()
            }
            else -> {
                // ROOT_TUN should not reach here (EasyTier runs in the root daemon
                // process, not in-process). Clear state so attachOrphanRoot can handle it.
                activeProfile = null
                webSession = null
            }
        }
    }

    private suspend fun startVpn(
        profile: EasyTierProfile,
        toml: String,
        globalSettings: GlobalSettings,
        clearInstances: Boolean = true,
    ): RuntimeStatus = try {
        if (clearInstances) EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError(R.string.error_easytier_start_failed) }
        vpn.start(profile, toml, globalSettings).also {
            publish(it)
            if (it.state == RuntimeState.STARTING || it.state == RuntimeState.RUNNING) pollVpn()
        }
    } catch (cancellation: CancellationException) {
        // External cancellation (e.g. BootCompletedReceiver's 8s budget): the EasyTier
        // core is already running in-process — do NOT release it or stop the VPN. The
        // caller intentionally bailed without waiting; a later app launch re-attaches
        // via attachRunningInstance().
        throw cancellation
    } catch (error: Throwable) {
        vpn.stop()
        if (clearInstances) runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError(R.string.error_easytier_start_failed))
    }

    private suspend fun startRoot(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        val status = root.start(profile, toml, globalSettings)
        publish(status)
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollRoot()
        return status
    }

    /**
     * no_tun path: start the EasyTier core only, with no VpnService/RootTun attached.
     * The core still publishes virtual_ipv4 / peers / hostname / natType via
     * collectNetworkInfos, so we poll those for UI updates — but never call
     * establishWhenResolved() or root.setTunFd(), leaving the system without a TUN.
     */
    private suspend fun startNoTun(
        profile: EasyTierProfile,
        toml: String,
        clearInstances: Boolean = true,
    ): RuntimeStatus = try {
        if (clearInstances) EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError(R.string.error_easytier_start_failed) }
        publish(RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, null, null, null))
        pollNoTun()
        mutableStatus.value
    } catch (cancellation: CancellationException) {
        // External cancellation (e.g. BootCompletedReceiver's 8s budget): the EasyTier
        // core is already running in-process — do NOT release it. The caller
        // intentionally bailed without waiting; a later app launch re-attaches via
        // attachRunningInstance().
        throw cancellation
    } catch (error: Throwable) {
        if (clearInstances) runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError(R.string.error_easytier_start_failed))
    }

    private suspend fun stopLocked(): RuntimeStatus {
        // cancelAndJoin (instead of cancel) ensures the poll coroutine has fully
        // terminated before we call stopService(). Otherwise a late establishWhenResolved()
        // call could post a startForegroundService intent AFTER stopService(), causing
        // EasyTierVpnService to be re-activated and leaving the system VPN up.
        pollJob?.cancelAndJoin()
        pollJob = null
        webSession?.let { session ->
            // Capture the latest effective config (the server can patch a running
            // instance without a new apply event) before the client removes it.
            val instance = session.primaryInstance
            if (!session.usingCache && !instance.isNullOrBlank()) {
                val toml = runCatching { webBridge.configToml(instance) }.getOrNull()
                cacheableWebConfig(toml)?.let { persistWebCache(session.profileId, it, System.currentTimeMillis()) }
            }
            stopWebSessionLocked(session)
        }
        webSession = null
        val profile = activeProfile
        when (profile?.tunMode) {
            // no_tun sessions started in-process (NO_TUN mode) release the
            // EasyTier instance here; ROOT_TUN sessions always live in the root
            // daemon and are stopped via root.stop() below.
            TunMode.NO_TUN -> {
                publish(mutableStatus.value.copy(state = RuntimeState.STOPPING))
                runCatching { EasyTierJni.retainNetworkInstance(null) }
            }
            TunMode.VPN_SERVICE -> {
                publish(mutableStatus.value.copy(state = RuntimeState.STOPPING))
                vpn.stop()
                runCatching { EasyTierJni.retainNetworkInstance(null) }
            }
            TunMode.ROOT_TUN -> root.stop()
            null -> return mutableStatus.value.takeIf { it.state == RuntimeState.STOPPED } ?: RuntimeStatus.Stopped
        }
        activeProfile = null
        return RuntimeStatus.Stopped.also { mutableStatus.value = it }
    }

    private fun pollVpn() {
        pollJob?.cancel()
        val profile = activeProfile ?: return
        pollJob = scope.launch {
            val exitDetector = CoreExitDetector()
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                // A null collect result (JNI threw) feeds the exit detector too:
                // silently skipping it previously left the VPN up forever when the
                // core was gone.
                val info = runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(profile.id) }
                    .onFailure { Log.w(TAG, "collectNetworkInfos failed: ${it.message}") }
                    .getOrNull()
                if (exitDetector.onPoll(info)) {
                    Log.w(TAG, "core for profile ${profile.id} is gone; tearing down VPN")
                    failure(profile, context.getString(R.string.error_core_stopped))
                    vpn.stop()
                    runCatching { EasyTierJni.retainNetworkInstance(null) }
                    return@launch
                }
                if (info != null) {
                    if (!info.error.isNullOrBlank()) {
                        failure(profile, info.error)
                        vpn.stop()
                        EasyTierJni.retainNetworkInstance(null)
                        return@launch
                    }
                    if (!info.virtualIpv4.isNullOrBlank()) {
                        val status = vpn.establishWhenResolved(profile, info.virtualIpv4, info.routes)
                        publish(status.copy(peers = info.peers, hostname = info.hostname, natType = info.natType))
                    } else {
                        publish(mutableStatus.value.copy(hostname = info.hostname, natType = info.natType))
                    }
                    refreshWireGuardPortal(profile)
                }
                // STARTING polls faster to resolve the virtual IPv4 quickly; steady-state
                // peers/latency change slowly, so 5s is enough and cuts JNI + JSON overhead.
                delay(if (mutableStatus.value.state == RuntimeState.RUNNING) 5_000 else 2_000)
            }
        }
    }

    private fun pollRoot() {
        pollJob?.cancel()
        pollJob = scope.launch { root.status.collect { publish(it) } }
    }

    /**
     * Refreshes the WireGuard VPN portal info for profiles that configured one.
     * collectNetworkInfos does not include the portal client config, so it is
     * queried via VpnPortalRpc and attached to the current status. No-op when
     * the portal is not configured or not started yet (fetch returns null).
     */
    private fun refreshWireGuardPortal(profile: EasyTierProfile) {
        if (profile.vpnPortal == null) return
        if (mutableStatus.value.state != RuntimeState.RUNNING) return
        WireGuardPortalClient.fetch(profile.id)?.let { portal ->
            publish(mutableStatus.value.copy(wireguardPortal = portal))
        }
    }

    private fun pollNoTun() {
        pollJob?.cancel()
        val profile = activeProfile ?: return
        pollJob = scope.launch {
            val exitDetector = CoreExitDetector()
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                val info = runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(profile.id) }
                    .onFailure { Log.w(TAG, "collectNetworkInfos failed: ${it.message}") }
                    .getOrNull()
                if (exitDetector.onPoll(info)) {
                    Log.w(TAG, "core for profile ${profile.id} is gone; clearing no-tun session")
                    failure(profile, context.getString(R.string.error_core_stopped))
                    runCatching { EasyTierJni.retainNetworkInstance(null) }
                    return@launch
                }
                // No TUN to establish: just surface runtime metadata for the UI.
                // EasyTier still assigns a virtual IPv4 even without a TUN device,
                // so propagate it (the poll only sets it when resolved, preserving
                // the previous value when collectNetworkInfos returns null).
                info?.let {
                    it.virtualIpv4?.let { ipv4 ->
                        publish(mutableStatus.value.copy(virtualIpv4 = ipv4))
                    }
                    publish(
                        mutableStatus.value.copy(
                            peers = it.peers,
                            hostname = it.hostname,
                            natType = it.natType,
                        ),
                    )
                    refreshWireGuardPortal(profile)
                }
                delay(5_000)
            }
        }
    }

    private fun failure(profile: EasyTierProfile, error: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, error).also { publish(it) }

    private fun nativeError(@StringRes fallbackRes: Int) = EasyTierJni.getLastError() ?: context.getString(fallbackRes)
}
