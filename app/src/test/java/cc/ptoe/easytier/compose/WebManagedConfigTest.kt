package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.ConfigServerEvent
import cc.ptoe.easytier.compose.core.WebConfigReplay
import cc.ptoe.easytier.compose.core.WebStartSource
import cc.ptoe.easytier.compose.core.cacheableWebConfig
import cc.ptoe.easytier.compose.core.configRpcPayload
import cc.ptoe.easytier.compose.core.parseConfigRpcResponse
import cc.ptoe.easytier.compose.core.parseConfigServerEvent
import cc.ptoe.easytier.compose.core.replayWebConfig
import cc.ptoe.easytier.compose.core.webCachedSummary
import cc.ptoe.easytier.compose.core.webProfileAfterSync
import cc.ptoe.easytier.compose.core.webStartSource
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.ProfileKind
import cc.ptoe.easytier.compose.data.TunMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagedConfigTest {

    private val deliveredToml = """
        instance_name = "lab"
        instance_id = "5f0d9b0e-2b40-4a3e-9f18-1c0f0b6a7d21"
        hostname = "server-side"
        dhcp = true
        listeners = ["tcp://0.0.0.0:11010"]

        [network_identity]
        network_name = "lab"
        network_secret = "s3cret"

        [[peer]]
        uri = "tcp://203.0.113.1:11010"

        [flags]
        mtu = 1420
        accept_dns = true
        no_tun = false
    """.trimIndent()

    private fun webProfile(
        cachedToml: String? = deliveredToml,
        cachedAt: Long? = 1_700_000_000_000,
        tunMode: TunMode = TunMode.VPN_SERVICE,
        hostname: String? = null,
    ) = EasyTierProfile(
        id = "profile-1",
        name = "Office",
        hostname = hostname,
        networkName = "",
        networkSecret = "",
        virtualIpv4 = null,
        dhcp = false,
        enableMagicDns = false,
        tunMode = tunMode,
        kind = ProfileKind.WEB_MANAGED,
        configServerUrl = "udp://example.com:22020/team",
        cachedToml = cachedToml,
        cachedAt = cachedAt,
    )

    // --- Core event parsing ------------------------------------------------

    @Test
    fun `parses applied and removed events`() {
        val applied = parseConfigServerEvent(
            """{"event":"run_network_instance","success":true,"instance_id":"id-1","instance_name":"lab","network_name":"lab","error":null}""",
        )
        assertEquals("lab", (applied as ConfigServerEvent.Applied).instanceName)
        assertEquals("id-1", applied.instanceId)

        val removed = parseConfigServerEvent(
            """{"event":"delete_network_instance","success":true,"instance_id":"id-1","instance_name":"lab","network_name":"lab","error":null}""",
        )
        assertEquals("lab", (removed as ConfigServerEvent.Removed).instanceName)
    }

    @Test
    fun `ignores failed, unknown and malformed events`() {
        assertEquals(
            ConfigServerEvent.Other,
            parseConfigServerEvent("""{"event":"run_network_instance","success":false,"instance_name":"lab"}"""),
        )
        assertEquals(
            ConfigServerEvent.Other,
            parseConfigServerEvent("""{"event":"something_else","success":true,"instance_name":"lab"}"""),
        )
        assertEquals(ConfigServerEvent.Other, parseConfigServerEvent("not json"))
        assertEquals(ConfigServerEvent.Other, parseConfigServerEvent("""{"event":"run_network_instance"}"""))
    }

    // --- Reading the delivered TOML ---------------------------------------

    @Test
    fun `builds the GetConfig payload and reads toml_config`() {
        assertEquals(
            """{"instance":{"instance_selector":{"name":"lab"}}}""",
            configRpcPayload("lab"),
        )
        assertEquals(
            deliveredToml,
            parseConfigRpcResponse("""{"config":{},"toml_config":${Json.encodeToString(deliveredToml)}}"""),
        )
        assertNull(parseConfigRpcResponse("""{"config":{}}"""))
        assertNull(parseConfigRpcResponse(null))
    }

    @Test
    fun `only importable server configs become a cache candidate`() {
        assertEquals(deliveredToml, cacheableWebConfig(deliveredToml))
        assertNull(cacheableWebConfig("[network_identity]\nnetwork_name = \"lab"))
        assertNull(cacheableWebConfig("network_name = \"lab\""))
        assertNull(cacheableWebConfig(""))
        assertNull(cacheableWebConfig(null))
    }

    @Test
    fun `failed sync never changes the cache`() {
        val profile = webProfile()
        assertEquals(profile, webProfileAfterSync(profile, null, 42))
        assertEquals(profile, webProfileAfterSync(profile, "", 42))
    }

    @Test
    fun `successful sync replaces the cache and its timestamp`() {
        val updated = webProfileAfterSync(webProfile(), deliveredToml, 99)
        assertEquals(deliveredToml, updated.cachedToml)
        assertEquals(99L, updated.cachedAt)
    }

    // --- Live vs cached decision ------------------------------------------

    @Test
    fun `uses live when the server applied a config`() {
        val source = webStartSource(webProfile(), deliveredToml, "lab")
        assertEquals(WebStartSource.Live("lab", deliveredToml), source)
    }

    @Test
    fun `falls back to the cache when the server delivered nothing`() {
        val source = webStartSource(webProfile(), null, null)
        assertEquals(WebStartSource.Cached(deliveredToml, 1_700_000_000_000), source)
    }

    @Test
    fun `reports unavailable when there is neither server config nor cache`() {
        assertEquals(
            WebStartSource.Unavailable,
            webStartSource(webProfile(cachedToml = null, cachedAt = null), null, null),
        )
    }

    // --- Cached replay ------------------------------------------------------

    @Test
    fun `replay keeps the network config but takes the profile identity`() {
        val replay = replayWebConfig(webProfile())
        assertTrue(replay is WebConfigReplay.Success)
        replay as WebConfigReplay.Success

        // The replay instance must not collide with the instance the core may
        // start for the config server in the background.
        assertTrue(replay.toml.contains("""instance_name = "profile-1""""))
        assertEquals("profile-1", replay.profile.id)
        assertEquals(TunMode.VPN_SERVICE, replay.profile.tunMode)
        // The server configuration itself is preserved.
        assertTrue(replay.toml.contains("""network_name = "lab""""))
        assertTrue(replay.toml.contains("""network_secret = "s3cret""""))
        assertTrue(replay.toml.contains("""uri = "tcp://203.0.113.1:11010""""))
        assertTrue(replay.toml.contains("dhcp = true"))
        assertEquals(1420, replay.profile.mtu)
        assertTrue(replay.profile.enableMagicDns)
        // No instance_id from the server: the core generates a fresh one.
        assertTrue(!replay.toml.contains("instance_id"))
    }

    @Test
    fun `replay applies the device hostname and global overrides`() {
        val replay = replayWebConfig(
            webProfile(hostname = "phone"),
            GlobalSettings(mtu = 1300),
        ) as WebConfigReplay.Success
        assertTrue(replay.toml.contains("""hostname = "phone""""))
        assertTrue(replay.toml.contains("mtu = 1300"))
        assertEquals(1300, replay.profile.mtu)
    }

    @Test
    fun `replay without a cache fails with the localized no-cache error`() {
        val replay = replayWebConfig(webProfile(cachedToml = null, cachedAt = null))
        assertEquals(
            R.string.error_web_no_cache,
            (replay as WebConfigReplay.Failure).messageRes,
        )
    }

    @Test
    fun `replay of an unusable cache fails instead of starting garbage`() {
        val replay = replayWebConfig(webProfile(cachedToml = "not toml"))
        assertEquals(
            R.string.error_web_cached_invalid,
            (replay as WebConfigReplay.Failure).messageRes,
        )
    }

    @Test
    fun `cached summary exposes the network only`() {
        val summary = webCachedSummary(deliveredToml)
        assertNotNull(summary)
        assertEquals("lab", summary!!.networkName)
        assertTrue(summary.dhcp)
        assertNull(webCachedSummary(null))
        assertNull(webCachedSummary("garbage"))
    }

    // --- Storage compatibility --------------------------------------------

    @Test
    fun `profiles stored before web-managed support stay static`() {
        val legacy = """
            [{"id":"p1","name":"Lab","networkName":"lab","networkSecret":"s","virtualIpv4":"10.0.0.2/24",
              "dhcp":false,"enableMagicDns":false,"tunMode":"VPN_SERVICE"}]
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val profiles = json.decodeFromString(ListSerializer(EasyTierProfile.serializer()), legacy)
        assertEquals(1, profiles.size)
        assertEquals(ProfileKind.STATIC, profiles[0].kind)
        assertNull(profiles[0].configServerUrl)
        assertNull(profiles[0].cachedToml)
        assertNull(profiles[0].cachedAt)
    }

    @Test
    fun `web-managed fields survive a serialization round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(ListSerializer(EasyTierProfile.serializer()), listOf(webProfile()))
        val decoded = json.decodeFromString(ListSerializer(EasyTierProfile.serializer()), encoded)[0]
        assertEquals(ProfileKind.WEB_MANAGED, decoded.kind)
        assertEquals("udp://example.com:22020/team", decoded.configServerUrl)
        assertEquals(deliveredToml, decoded.cachedToml)
        assertEquals(1_700_000_000_000L, decoded.cachedAt)
    }
}
