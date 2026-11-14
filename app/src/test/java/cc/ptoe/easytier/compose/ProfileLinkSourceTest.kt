package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.ConfigText
import cc.ptoe.easytier.compose.core.ProfileLinkResult
import cc.ptoe.easytier.compose.core.ProfileLinkSource
import cc.ptoe.easytier.compose.core.withImportedConfig
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.TunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class ProfileLinkSourceTest {

    @Test
    fun `share links are decoded without a network request`() {
        var fetched = false
        val link = shareLink("""{"network_name":"mesh","network_secret":"secret","dhcp":true}""")
        val result = ProfileLinkSource.load(link) { _: URI ->
            fetched = true
            error("must not fetch a share link")
        }

        assertFalse(fetched)
        assertTrue(result is ProfileLinkResult.Success)
        val profile = (result as ProfileLinkResult.Success).profile
        assertEquals("mesh", profile.networkName)
        assertEquals(link, profile.sourceUrl)
    }

    @Test
    fun `http urls are fetched once and parsed as TOML`() {
        val toml = """
            hostname = "node-a"
            dhcp = true

            [network_identity]
            network_name = "mesh"
            network_secret = "secret"
        """.trimIndent()
        var fetched: URI? = null
        val url = "https://example.com/config.toml"
        val result = ProfileLinkSource.load(url) { uri: URI ->
            fetched = uri
            ConfigText(toml, tooLarge = false)
        }

        assertEquals(URI(url), fetched)
        assertTrue(result is ProfileLinkResult.Success)
        val profile = (result as ProfileLinkResult.Success).profile
        assertEquals("mesh", profile.networkName)
        assertEquals("node-a", profile.hostname)
        assertEquals(url, profile.sourceUrl)
    }

    @Test
    fun `config server urls are rejected without a network request`() {
        var fetched = false
        val result = ProfileLinkSource.load("udp://host:22020/user") { _: URI ->
            fetched = true
            ConfigText("", tooLarge = false)
        }

        assertFalse(fetched)
        assertEquals(
            R.string.profiles_import_web_console_url,
            (result as ProfileLinkResult.Failure).messageRes,
        )
    }

    @Test
    fun `non http schemes and malformed urls are rejected`() {
        listOf("ftp://example.com/config.toml", "not a url", "").forEach { value ->
            val result = ProfileLinkSource.load(value) { _: URI ->
                error("must not fetch $value")
            }
            assertTrue(result is ProfileLinkResult.Failure)
            assertEquals(R.string.profiles_import_invalid_url, (result as ProfileLinkResult.Failure).messageRes)
        }
    }

    @Test
    fun `oversized documents are rejected`() {
        val result = ProfileLinkSource.load("https://example.com/config.toml") { _: URI ->
            ConfigText("", tooLarge = true)
        }
        assertEquals(
            R.string.profiles_import_too_large,
            (result as ProfileLinkResult.Failure).messageRes,
        )
    }

    @Test
    fun `refresh keeps identity name tun mode and device options`() {
        val existing = EasyTierProfile(
            id = "profile-id",
            name = "My name",
            networkName = "old",
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
            sourceUrl = "https://example.com/config.toml",
        )
        val imported = existing.copy(
            id = "other-id",
            name = "Remote name",
            networkName = "new",
            networkSecret = "new",
            dhcp = false,
            virtualIpv4 = "10.0.0.2/24",
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
        )

        val merged = existing.withImportedConfig(imported)

        assertEquals("profile-id", merged.id)
        assertEquals("My name", merged.name)
        assertEquals("new", merged.networkName)
        assertEquals("new", merged.networkSecret)
        assertEquals("10.0.0.2/24", merged.virtualIpv4)
        assertFalse(merged.dhcp)
        assertTrue(merged.enableMagicDns)
        assertEquals(TunMode.ROOT_TUN, merged.tunMode)
        assertEquals("easytier9", merged.tunDeviceName)
        assertTrue(merged.startOnBoot)
        assertEquals(1400, merged.mtu)
        assertFalse(merged.multiThread)
        assertEquals(4, merged.multiThreadCount)
        assertEquals(1L, merged.foreignRelayBpsLimit)
        assertEquals(2L, merged.instanceRecvBpsLimit)
        assertEquals(99L, merged.socketMark)
        assertEquals("https://example.com/config.toml", merged.sourceUrl)
        assertNull(merged.hostname)
    }

    private fun shareLink(json: String): String {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(json.toByteArray()) }
        val cfg = Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        return "https://easytier.cn/comp_cfg?cfg=${URLEncoder.encode(cfg, Charsets.UTF_8.name())}"
    }
}
