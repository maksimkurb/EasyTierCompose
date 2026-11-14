package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.EasyTierWebLink
import cc.ptoe.easytier.compose.core.WebLinkResult
import cc.ptoe.easytier.compose.data.AclAction
import cc.ptoe.easytier.compose.data.AclChainType
import cc.ptoe.easytier.compose.data.AclProtocol
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.TunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class EasyTierWebLinkTest {

    @Test
    fun `decodes a link written with proto field numbers`() {
        val link = shareLink(
            """
            {
              "1": "0c4b33ba-4ed5-42d8-9095-21b786c66e94",
              "2": true,
              "5": "phone",
              "6": "mynet",
              "7": "s3cret",
              "8": 1,
              "10": ["tcp://public.easytier.top:11010"],
              "11": ["192.168.1.0/24", "10.0.0.0/24->192.168.2.0/24"],
              "17": ["tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"],
              "19": true,
              "25": true,
              "29": true,
              "32": true,
              "33": ["172.16.0.0/16"],
              "40": 1400,
              "42": true,
              "48": [{"bind_ip":"127.0.0.1","bind_port":8080,"dst_ip":"10.0.0.2","dst_port":80,"proto":"tcp"}],
              "52": 2,
              "53": "aes-gcm",
              "56": {"acl_v1": {"chains": [{"name": "c1", "chain_type": 3, "default_action": 1, "rules": [{"name": "r1", "protocol": 1, "ports": ["80"], "action": 2}]}], "group": {"declares": [{"group_name": "g", "group_secret": "s"}], "members": ["m"]}}},
              "68": [{"uri": "tcp://peer:11010", "peer_public_key": "pk"}],
              "71": [{"credential_id": "id", "credential_secret": "sec", "groups": ["g"], "allow_relay": true, "allowed_proxy_cidrs": ["10.0.0.0/8"], "expiry_unix": 123, "reusable": false}]
            }
            """.trimIndent(),
            name = "My Shared Net",
        )

        val result = EasyTierWebLink.parse(link)
        assertTrue(result is WebLinkResult.Success)
        val profile = (result as WebLinkResult.Success).profile

        assertEquals("My Shared Net", profile.name)
        assertEquals("phone", profile.hostname)
        assertEquals("mynet", profile.networkName)
        assertEquals("s3cret", profile.networkSecret)
        assertTrue(profile.dhcp)
        assertNull(profile.virtualIpv4)
        assertEquals(listOf("tcp://peer:11010"), profile.peers.map { it.uri })
        assertEquals("pk", profile.peers.single().peerPublicKey)
        assertEquals(listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"), profile.listeners)
        assertEquals(listOf("192.168.1.0/24", "10.0.0.0/24"), profile.proxyNetworks.map { it.cidr })
        assertEquals("192.168.2.0/24", profile.proxyNetworks[1].mappedCidr)
        assertEquals(listOf("172.16.0.0/16"), profile.manualRoutes)
        assertEquals(1400, profile.mtu)
        assertEquals("easytier0", profile.tunDeviceName)
        assertTrue(profile.enableMagicDns)
        assertTrue(profile.latencyFirst)
        assertEquals(CompressionAlgo.Zstd, profile.dataCompressAlgo)
        assertEquals(EncryptionAlgorithm.AesGcm, profile.encryptionAlgorithm)
        assertEquals(1, profile.portForwards.size)
        assertEquals("127.0.0.1:8080", profile.portForwards.single().bindAddr)
        assertEquals("10.0.0.2:80", profile.portForwards.single().dstAddr)
        assertEquals("tcp", profile.portForwards.single().proto)
        assertEquals(TunMode.VPN_SERVICE, profile.tunMode)
        assertEquals(1, profile.acl?.chains?.size)
        assertEquals(AclChainType.Forward, profile.acl?.chains?.single()?.chainType)
        assertEquals(AclProtocol.TCP, profile.acl?.chains?.single()?.rules?.single()?.protocol)
        assertEquals(AclAction.Drop, profile.acl?.chains?.single()?.rules?.single()?.action)
        assertEquals("g", profile.acl?.group?.declares?.single()?.groupName)
        assertEquals(listOf("m"), profile.acl?.group?.members)
        assertEquals("id", profile.managedCredentials.single().credentialId)
        assertEquals(false, profile.managedCredentials.single().reusable)
    }

    @Test
    fun `accepts named keys and falls back to the network name`() {
        val link = shareLink(
            """
            {
              "network_name": "mesh",
              "network_secret": "secret",
              "dhcp": false,
              "virtual_ipv4": "10.1.2.3",
              "network_length": 16,
              "listener_urls": ["tcp://0.0.0.0:11010"]
            }
            """.trimIndent(),
        )

        val result = EasyTierWebLink.parse(link)
        assertTrue(result is WebLinkResult.Success)
        val profile = (result as WebLinkResult.Success).profile
        assertEquals("mesh", profile.name)
        assertEquals("10.1.2.3/16", profile.virtualIpv4)
        assertEquals(TunMode.VPN_SERVICE, profile.tunMode)
    }

    @Test
    fun `public server method falls back to the public server URL`() {
        val link = shareLink(
            """
            {"network_name":"mesh","networking_method":0,"public_server_url":"tcp://public.easytier.top:11010"}
            """.trimIndent(),
        )

        val profile = (EasyTierWebLink.parse(link) as WebLinkResult.Success).profile
        assertEquals(listOf("tcp://public.easytier.top:11010"), profile.peers.map { it.uri })
    }

    @Test
    fun `standalone method ignores peer urls`() {
        val link = shareLink(
            """
            {"network_name":"mesh","networking_method":2,"peer_urls":["tcp://peer:11010"]}
            """.trimIndent(),
        )

        val profile = (EasyTierWebLink.parse(link) as WebLinkResult.Success).profile
        assertEquals(emptyList<String>(), profile.peers.map { it.uri })
    }

    @Test
    fun `no_tun and socket_mark select the TUN mode`() {
        val noTun = shareLink("""{"network_name":"mesh","no_tun":true}""")
        assertEquals(
            TunMode.NO_TUN,
            (EasyTierWebLink.parse(noTun) as WebLinkResult.Success).profile.tunMode,
        )

        val root = shareLink("""{"network_name":"mesh","socket_mark":131072}""")
        val rootProfile = (EasyTierWebLink.parse(root) as WebLinkResult.Success).profile
        assertEquals(TunMode.ROOT_TUN, rootProfile.tunMode)
        assertEquals(131072L, rootProfile.socketMark)
    }

    @Test
    fun `rejects malformed payloads with a localized error`() {
        val noCfg = EasyTierWebLink.parse("https://easytier.cn/comp_cfg?name=x")
        assertEquals(R.string.profiles_import_invalid_share_link, (noCfg as WebLinkResult.Failure).messageRes)

        val badBase64 = EasyTierWebLink.parse("https://easytier.cn/comp_cfg?cfg=!not-base64!")
        assertEquals(R.string.profiles_import_invalid_share_link, (badBase64 as WebLinkResult.Failure).messageRes)

        val badZlib = EasyTierWebLink.parse(shareLinkRaw(Base64.getUrlEncoder().withoutPadding().encodeToString("not zlib".toByteArray())))
        assertEquals(R.string.profiles_import_invalid_share_link, (badZlib as WebLinkResult.Failure).messageRes)

        val badJson = EasyTierWebLink.parse(shareLinkRaw(encode("not json")))
        assertEquals(R.string.profiles_import_invalid_share_link, (badJson as WebLinkResult.Failure).messageRes)
    }

    @Test
    fun `rejects a share link without network name`() {
        val link = shareLink("""{"network_secret":"secret"}""")
        val result = EasyTierWebLink.parse(link)
        assertEquals(
            R.string.profiles_import_missing_network_name,
            (result as WebLinkResult.Failure).messageRes,
        )
    }

    @Test
    fun `ignores URLs that are not EasyTier share links`() {
        assertEquals(WebLinkResult.NotShareLink, EasyTierWebLink.parse("https://example.com/comp_cfg?cfg=x"))
        assertEquals(WebLinkResult.NotShareLink, EasyTierWebLink.parse("https://easytier.cn/other?cfg=x"))
        assertEquals(WebLinkResult.NotShareLink, EasyTierWebLink.parse("udp://host:22020/user"))
        assertEquals(WebLinkResult.NotShareLink, EasyTierWebLink.parse("not a url"))
    }

    private fun shareLink(json: String, name: String? = null): String = shareLinkRaw(encode(json), name)

    private fun shareLinkRaw(cfg: String, name: String? = null): String {
        val encoded = URLEncoder.encode(cfg, Charsets.UTF_8.name())
        val suffix = name?.let { "&name=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
        return "https://easytier.cn/comp_cfg?cfg=$encoded$suffix"
    }

    private fun encode(json: String): String {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(json.toByteArray()) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
