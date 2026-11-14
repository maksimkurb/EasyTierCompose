package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.AUTO_REFRESH_INTERVAL_MILLIS
import cc.ptoe.easytier.compose.core.AutoRefreshResult
import cc.ptoe.easytier.compose.core.ConfigText
import cc.ptoe.easytier.compose.core.ProfileLinkResult
import cc.ptoe.easytier.compose.core.ProfileLinkSource
import cc.ptoe.easytier.compose.core.autoRefreshProfile
import cc.ptoe.easytier.compose.core.shouldAutoRefresh
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.TunMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI

class ProfileAutoRefreshTest {

    // --- 30-minute policy -------------------------------------------------

    @Test
    fun `a profile that was never synced refreshes`() {
        assertTrue(shouldAutoRefresh(profile(syncedAt = null), NOW))
    }

    @Test
    fun `a profile synced 29 minutes 59 seconds ago is still fresh`() {
        assertFalse(shouldAutoRefresh(profile(syncedAt = NOW - 1_799_000L), NOW))
    }

    @Test
    fun `a profile synced exactly 30 minutes ago refreshes`() {
        assertTrue(shouldAutoRefresh(profile(syncedAt = NOW - AUTO_REFRESH_INTERVAL_MILLIS), NOW))
    }

    @Test
    fun `the interval and the clock are injectable`() {
        val synced = profile(syncedAt = 1_000L)
        assertFalse(shouldAutoRefresh(synced, nowMillis = 60_999L, intervalMillis = 60_000L))
        assertTrue(shouldAutoRefresh(synced, nowMillis = 61_000L, intervalMillis = 60_000L))
        // Same profile, later clock: the decision changes without touching storage.
        assertFalse(shouldAutoRefresh(profile(syncedAt = 1_000L), nowMillis = 2_000L))
        assertTrue(shouldAutoRefresh(profile(syncedAt = 1_000L), nowMillis = 1_801_000L))
    }

    @Test
    fun `auto refresh switched off never refreshes`() {
        assertFalse(shouldAutoRefresh(profile(syncedAt = null, autoRefresh = false), NOW))
    }

    @Test
    fun `a profile without a source url never refreshes`() {
        assertFalse(shouldAutoRefresh(profile(sourceUrl = null, syncedAt = null), NOW))
        assertFalse(shouldAutoRefresh(profile(sourceUrl = "   ", syncedAt = null), NOW))
    }

    // --- fallback on failure ---------------------------------------------

    @Test
    fun `a failed fetch leaves the stored profile untouched`() {
        val stored = profile()
        val snapshot = stored.copy()

        val result = autoRefreshProfile(
            profile = stored,
            nowMillis = NOW,
            load = { throw IOException("offline") },
            validate = { error("validation must not run without a fetched config") },
        )

        assertTrue(result is AutoRefreshResult.Failure)
        assertEquals("example.com", (result as AutoRefreshResult.Failure).host)
        // Nothing to persist: the coordinator starts the copy it already has.
        assertEquals(snapshot, stored)
        assertEquals(1_000L, stored.sourceSyncedAt)
    }

    @Test
    fun `a config server url fails without a network request`() {
        var fetched = false
        val result = autoRefreshProfile(
            profile = profile(sourceUrl = "udp://config.example.com:22020/user"),
            nowMillis = NOW,
            load = {
                // Reuses the real importer: udp/tcp/ws/wss/ring are rejected
                // locally, exactly like the user-facing import does.
                ProfileLinkSource.load(it) { _: URI ->
                    fetched = true
                    ConfigText("", tooLarge = false)
                }
            },
            validate = { true },
        )

        assertTrue(result is AutoRefreshResult.Failure)
        assertEquals("config.example.com", (result as AutoRefreshResult.Failure).host)
        assertFalse(fetched)
    }

    @Test
    fun `a failure reason drops url query strings before it is logged`() {
        val result = autoRefreshProfile(
            profile = profile(),
            nowMillis = NOW,
            load = { ProfileLinkResult.Failure("bad link?cfg=SECRET") },
            validate = { true },
        )

        val reason = (result as AutoRefreshResult.Failure).reason
        assertEquals("bad link", reason)
        assertFalse(reason.contains("SECRET"))
    }

    @Test
    fun `a configuration rejected by the start validator is a failed refresh`() {
        val result = autoRefreshProfile(
            profile = profile(),
            nowMillis = NOW,
            load = { ProfileLinkResult.Success(profile(networkName = "new")) },
            validate = { false },
        )

        assertTrue(result is AutoRefreshResult.Failure)
    }

    // --- success ----------------------------------------------------------

    @Test
    fun `a successful refresh updates the config and keeps device local options`() {
        val stored = profile(syncedAt = 1_000L)
        val imported = stored.copy(
            id = "remote-id",
            name = "Remote name",
            networkName = "new",
            networkSecret = "new",
            virtualIpv4 = "10.0.0.2/24",
            dhcp = false,
            enableMagicDns = true,
            tunMode = TunMode.VPN_SERVICE,
            tunDeviceName = "remote0",
            startOnBoot = false,
            mtu = 1380,
            multiThread = true,
            multiThreadCount = 2,
            foreignRelayBpsLimit = 10,
            instanceRecvBpsLimit = 20,
            socketMark = null,
            sourceUrl = null,
            sourceSyncedAt = 5_000L,
            autoRefresh = false,
        )

        val result = autoRefreshProfile(stored, NOW, { ProfileLinkResult.Success(imported) }, { true })

        assertTrue(result is AutoRefreshResult.Success)
        val refreshed = (result as AutoRefreshResult.Success).profile
        assertEquals("profile-id", refreshed.id)
        assertEquals("My name", refreshed.name)
        assertEquals("new", refreshed.networkName)
        assertEquals("new", refreshed.networkSecret)
        assertEquals("10.0.0.2/24", refreshed.virtualIpv4)
        assertFalse(refreshed.dhcp)
        assertTrue(refreshed.enableMagicDns)
        assertEquals(TunMode.ROOT_TUN, refreshed.tunMode)
        assertEquals("easytier9", refreshed.tunDeviceName)
        assertTrue(refreshed.startOnBoot)
        assertEquals(1400, refreshed.mtu)
        assertFalse(refreshed.multiThread)
        assertEquals(4, refreshed.multiThreadCount)
        assertEquals(1L, refreshed.foreignRelayBpsLimit)
        assertEquals(2L, refreshed.instanceRecvBpsLimit)
        assertEquals(99L, refreshed.socketMark)
        assertEquals("https://example.com/config.toml", refreshed.sourceUrl)
        assertTrue(refreshed.autoRefresh)
        assertEquals(NOW, refreshed.sourceSyncedAt)
    }

    @Test
    fun `the stored profile is not mutated by a successful refresh`() {
        val stored = profile(syncedAt = 1_000L)
        val snapshot = stored.copy()

        autoRefreshProfile(stored, NOW, { ProfileLinkResult.Success(profile(networkName = "new")) }, { true })

        assertEquals(snapshot, stored)
    }

    // --- persistence backward compatibility -------------------------------

    @Test
    fun `a profile stored before auto refresh existed still deserializes`() {
        val legacy = """
            {
              "id": "p1",
              "name": "Old profile",
              "networkName": "mesh",
              "networkSecret": "secret",
              "virtualIpv4": null,
              "dhcp": true,
              "enableMagicDns": false,
              "tunMode": "VPN_SERVICE",
              "sourceUrl": "https://example.com/config.toml"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(EasyTierProfile.serializer(), legacy)

        assertEquals("https://example.com/config.toml", decoded.sourceUrl)
        assertNull(decoded.sourceSyncedAt)
        assertTrue(decoded.autoRefresh)
    }

    @Test
    fun `the new fields survive a serialization round trip`() {
        val encoded = Json.encodeToString(EasyTierProfile.serializer(), profile(syncedAt = NOW, autoRefresh = false))
        val restored = Json.decodeFromString(EasyTierProfile.serializer(), encoded)

        assertEquals(NOW, restored.sourceSyncedAt)
        assertFalse(restored.autoRefresh)
        assertEquals("https://example.com/config.toml", restored.sourceUrl)
    }

    private fun profile(
        networkName: String = "old",
        sourceUrl: String? = "https://example.com/config.toml",
        syncedAt: Long? = 1_000L,
        autoRefresh: Boolean = true,
    ) = EasyTierProfile(
        id = "profile-id",
        name = "My name",
        networkName = networkName,
        networkSecret = "old",
        virtualIpv4 = null,
        dhcp = true,
        enableMagicDns = false,
        tunMode = TunMode.ROOT_TUN,
        tunDeviceName = "easytier9",
        startOnBoot = true,
        mtu = 1400,
        multiThread = false,
        multiThreadCount = 4,
        foreignRelayBpsLimit = 1,
        instanceRecvBpsLimit = 2,
        socketMark = 99,
        sourceUrl = sourceUrl,
        sourceSyncedAt = syncedAt,
        autoRefresh = autoRefresh,
    )

    private companion object {
        const val NOW = 10_000_000_000L
    }
}
