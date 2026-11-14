package cc.ptoe.easytier.compose.core

import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.Acl
import cc.ptoe.easytier.compose.data.AclAction
import cc.ptoe.easytier.compose.data.AclChain
import cc.ptoe.easytier.compose.data.AclChainType
import cc.ptoe.easytier.compose.data.AclGroup
import cc.ptoe.easytier.compose.data.AclGroupDeclare
import cc.ptoe.easytier.compose.data.AclProtocol
import cc.ptoe.easytier.compose.data.AclRule
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.ManagedCredential
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork
import cc.ptoe.easytier.compose.data.SecureMode
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.data.VpnPortalClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes EasyTier's `https://easytier.cn/comp_cfg?cfg=...` configuration share
 * links, the format produced by the EasyTier HarmonyOS client and consumed by
 * other EasyTier clients.
 *
 * The payload is a zlib-compressed, URL-safe base64 JSON object whose top-level
 * keys are `api.manage.NetworkConfig` proto field numbers (the schema is mapped
 * back to field names here). It is a *configuration*, not a config-server URL:
 * everything needed to build a profile is inside the link, so importing one
 * never performs a network request.
 *
 * The app still accepts plain TOML over HTTP(S) through [ProfileLinkSource];
 * this class only handles the encoded share-link form.
 */
sealed interface WebLinkResult {
    data class Success(val profile: EasyTierProfile) : WebLinkResult

    /**
     * [message] is an English, log-friendly description. [messageRes] is the
     * localized string the UI should show when present.
     */
    data class Failure(
        val message: String,
        @param:StringRes val messageRes: Int? = null,
    ) : WebLinkResult

    /** The input is not an EasyTier share link; the caller may try another format. */
    data object NotShareLink : WebLinkResult
}

object EasyTierWebLink {
    const val HOST = "easytier.cn"
    const val PATH = "/comp_cfg"

    /** Upper bound for both the compressed payload and the decoded JSON. */
    private const val MAX_PAYLOAD_BYTES = 1_048_576

    private data class Decoded(val json: JsonObject, val name: String?)

    /**
     * Parses [link]. Returns [WebLinkResult.NotShareLink] when the URL is not an
     * EasyTier share link, so the caller can fall back to a plain HTTP(S) fetch.
     */
    fun parse(link: String): WebLinkResult {
        if (link.length > MAX_PAYLOAD_BYTES) {
            return WebLinkResult.Failure("Share link is too large", R.string.profiles_import_too_large)
        }
        val uri = try {
            URI(link.trim())
        } catch (_: Exception) {
            // Not a URL we can classify; the caller reports the invalid URL.
            return WebLinkResult.NotShareLink
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return WebLinkResult.NotShareLink
        if (uri.host?.equals(HOST, ignoreCase = true) != true || uri.path != PATH) {
            return WebLinkResult.NotShareLink
        }

        val query = try {
            parseQuery(uri.rawQuery)
        } catch (error: Exception) {
            return failure("Invalid share link query: ${error.message}")
        }
        val cfg = query["cfg"]?.takeIf(String::isNotBlank)
            ?: return failure("Share link has no cfg parameter")

        val bytes = when (val inflated = inflate(cfg)) {
            is InflateResult.Success -> inflated.bytes
            InflateResult.TooLarge -> return WebLinkResult.Failure(
                "Share link payload is too large",
                R.string.profiles_import_too_large,
            )

            InflateResult.Invalid -> return failure("Share link payload is not valid zlib data")
        }
        val decoded = when (val payload = decodeJson(bytes)) {
            is JsonResult.Success -> payload.payload
            is JsonResult.Failure -> return payload.result
        }
        return buildProfile(decoded.json, query["name"] ?: decoded.name)
    }

    private fun failure(message: String): WebLinkResult.Failure =
        WebLinkResult.Failure(message, R.string.profiles_import_invalid_share_link)

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
            val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
            key to value
        }.toMap()
    }

    private sealed interface InflateResult {
        data class Success(val bytes: ByteArray) : InflateResult
        data object TooLarge : InflateResult
        data object Invalid : InflateResult
    }

    private fun inflate(encoded: String): InflateResult {
        val compressed = decodeBase64Url(encoded) ?: return InflateResult.Invalid
        if (compressed.size > MAX_PAYLOAD_BYTES) return InflateResult.TooLarge

        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return InflateResult.Invalid
                }
                if (read == 0) {
                    // Defensive: no progress and no more input means a truncated
                    // or malformed stream; otherwise let the loop reach finished().
                    if (inflater.needsInput() || inflater.needsDictionary()) return InflateResult.Invalid
                    continue
                }
                output.write(buffer, 0, read)
                if (output.size() > MAX_PAYLOAD_BYTES) return InflateResult.TooLarge
            }
            InflateResult.Success(output.toByteArray())
        } finally {
            inflater.end()
        }
    }

    private sealed interface JsonResult {
        data class Success(val payload: Decoded) : JsonResult
        data class Failure(val result: WebLinkResult.Failure) : JsonResult
    }

    private fun decodeJson(bytes: ByteArray): JsonResult {
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            return JsonResult.Failure(
                WebLinkResult.Failure("Share link payload is too large", R.string.profiles_import_too_large),
            )
        }
        val root = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return JsonResult.Failure(failure("Share link payload is not a JSON object"))

        val normalized = normalizeKeys(root)
        val name = normalized.string("network_name")?.trim()?.takeIf(String::isNotEmpty)
        return JsonResult.Success(Decoded(normalized, name))
    }

    // `api.manage.NetworkConfig` field numbers. Links written by the HarmonyOS
    // client use numbers; accepting the names too keeps hand-written payloads
    // and future clients working.
    private val FIELD_NAMES: Map<Int, String> = mapOf(
        1 to "instance_id",
        2 to "dhcp",
        3 to "virtual_ipv4",
        4 to "network_length",
        5 to "hostname",
        6 to "network_name",
        7 to "network_secret",
        8 to "networking_method",
        9 to "public_server_url",
        10 to "peer_urls",
        11 to "proxy_cidrs",
        12 to "enable_vpn_portal",
        13 to "vpn_portal_listen_port",
        14 to "vpn_portal_client_network_addr",
        15 to "vpn_portal_client_network_len",
        16 to "advanced_settings",
        17 to "listener_urls",
        19 to "latency_first",
        20 to "dev_name",
        21 to "use_smoltcp",
        22 to "enable_kcp_proxy",
        23 to "disable_kcp_input",
        24 to "disable_p2p",
        25 to "bind_device",
        26 to "no_tun",
        27 to "enable_exit_node",
        28 to "relay_all_peer_rpc",
        29 to "multi_thread",
        30 to "enable_relay_network_whitelist",
        31 to "relay_network_whitelist",
        32 to "enable_manual_routes",
        33 to "routes",
        34 to "exit_nodes",
        35 to "proxy_forward_by_system",
        36 to "disable_encryption",
        37 to "enable_socks5",
        38 to "socks5_port",
        39 to "disable_udp_hole_punching",
        40 to "mtu",
        41 to "mapped_listeners",
        42 to "enable_magic_dns",
        43 to "enable_private_mode",
        45 to "enable_quic_proxy",
        46 to "disable_quic_input",
        47 to "disable_ipv6",
        48 to "port_forwards",
        49 to "disable_sym_hole_punching",
        50 to "quic_listen_port",
        51 to "p2p_only",
        52 to "data_compress_algo",
        53 to "encryption_algorithm",
        54 to "disable_tcp_hole_punching",
        55 to "secure_mode",
        56 to "acl",
        57 to "credential_file",
        58 to "lazy_p2p",
        59 to "need_p2p",
        60 to "instance_recv_bps_limit",
        61 to "disable_upnp",
        62 to "ipv6_public_addr_provider",
        63 to "ipv6_public_addr_auto",
        64 to "ipv6_public_addr_prefix",
        65 to "disable_relay_data",
        66 to "enable_udp_broadcast_relay",
        67 to "socket_mark",
        68 to "peers",
        69 to "vpn_portal_config",
        71 to "managed_credentials",
    )

    private fun normalizeKeys(source: JsonObject): JsonObject = JsonObject(
        source.mapKeys { (key, _) -> FIELD_NAMES[key.toIntOrNull()] ?: key },
    )

    @Suppress("UNCHECKED_CAST")
    private fun buildProfile(root: JsonObject, linkName: String?): WebLinkResult {
        val networkName = root.string("network_name")?.trim().orEmpty()
        if (networkName.isEmpty()) {
            return WebLinkResult.Failure(
                "network_name is missing in the shared configuration",
                R.string.profiles_import_missing_network_name,
            )
        }

        val dhcp = root.bool("dhcp") ?: false
        val virtualIpv4 = root.string("virtual_ipv4")?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            if ('/' in value) value else "${value}/${root.int("network_length") ?: 24}"
        }
        val socketMark = root.long("socket_mark")
        val noTun = root.bool("no_tun") == true
        val tunMode = when {
            noTun -> TunMode.NO_TUN
            socketMark != null -> TunMode.ROOT_TUN
            else -> TunMode.VPN_SERVICE
        }
        val networkingMethod = root.intOrName(
            "networking_method",
            mapOf("publicserver" to 0, "public_server" to 0, "manual" to 1, "standalone" to 2),
        )
        val peers = root.peers(networkingMethod)

        return WebLinkResult.Success(
            EasyTierProfile(
                id = UUID.randomUUID().toString(),
                name = linkName?.trim()?.takeIf(String::isNotEmpty) ?: networkName,
                hostname = root.string("hostname")?.trim()?.takeIf(String::isNotEmpty),
                networkName = networkName,
                networkSecret = root.string("network_secret").orEmpty(),
                peers = peers,
                listeners = root.strings("listener_urls"),
                mappedListeners = root.strings("mapped_listeners"),
                virtualIpv4 = virtualIpv4?.takeUnless { dhcp },
                dhcp = dhcp,
                proxyNetworks = root.proxyNetworks(),
                manualRoutes = if (root.bool("enable_manual_routes") == true) root.strings("routes") else emptyList(),
                exitNodes = root.strings("exit_nodes"),
                enableMagicDns = root.bool("enable_magic_dns") ?: false,
                tunMode = tunMode,
                ipv6PublicAddrProvider = root.bool("ipv6_public_addr_provider") ?: false,
                ipv6PublicAddrAuto = root.bool("ipv6_public_addr_auto") ?: false,
                ipv6PublicAddrPrefix = root.string("ipv6_public_addr_prefix")?.trim()?.takeIf(String::isNotEmpty),
                portForwards = root.portForwards(),
                socks5Proxy = if (root.bool("enable_socks5") == true) {
                    "socks5://0.0.0.0:${root.int("socks5_port") ?: 1080}"
                } else {
                    null
                },
                vpnPortal = root.vpnPortal(),
                secureMode = root.secureMode(),
                defaultProtocol = "tcp",
                enableEncryption = !(root.bool("disable_encryption") ?: false),
                enableIpv6 = !(root.bool("disable_ipv6") ?: false),
                latencyFirst = root.bool("latency_first") ?: false,
                enableExitNode = root.bool("enable_exit_node") ?: false,
                relayNetworkWhitelist = if (root.bool("enable_relay_network_whitelist") == true) {
                    root.strings("relay_network_whitelist").joinToString(" ")
                } else {
                    "*"
                },
                disableP2p = root.bool("disable_p2p") ?: false,
                p2pOnly = root.bool("p2p_only") ?: false,
                lazyP2p = root.bool("lazy_p2p") ?: false,
                needP2p = root.bool("need_p2p") ?: false,
                relayAllPeerRpc = root.bool("relay_all_peer_rpc") ?: false,
                disableTcpHolePunching = root.bool("disable_tcp_hole_punching") ?: false,
                disableUdpHolePunching = root.bool("disable_udp_hole_punching") ?: false,
                disableSymHolePunching = root.bool("disable_sym_hole_punching") ?: false,
                disableUpnp = root.bool("disable_upnp") ?: false,
                dataCompressAlgo = root.compressionAlgorithm(),
                bindDevice = root.bool("bind_device") ?: true,
                enableKcpProxy = root.bool("enable_kcp_proxy") ?: false,
                disableKcpInput = root.bool("disable_kcp_input") ?: false,
                proxyForwardBySystem = root.bool("proxy_forward_by_system") ?: false,
                privateMode = root.bool("enable_private_mode") ?: false,
                enableQuicProxy = root.bool("enable_quic_proxy") ?: false,
                disableQuicInput = root.bool("disable_quic_input") ?: false,
                encryptionAlgorithm = root.encryptionAlgorithm(),
                tunDeviceName = root.string("dev_name")?.trim()?.ifBlank { "easytier0" } ?: "easytier0",
                mtu = root.int("mtu") ?: 1380,
                multiThread = root.bool("multi_thread") ?: true,
                instanceRecvBpsLimit = root.long("instance_recv_bps_limit") ?: Long.MAX_VALUE,
                socketMark = socketMark,
                acl = root.acl(),
                managedCredentials = root.managedCredentials(),
                credentialFile = root.string("credential_file")?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    // --- JSON helpers ------------------------------------------------------

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value.isString) return value.content
        return null
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.booleanOrNull ?: when (value.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull
    }

    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.longOrNull
    }

    private fun JsonObject.intOrName(key: String, names: Map<String, Int> = emptyMap()): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: names[value.content.lowercase()]
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.strings(key: String): List<String> =
        array(key)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()

    private fun JsonObject.peers(networkingMethod: Int?): List<Peer> {
        val explicit = array("peers")?.mapNotNull { element ->
            val peer = element as? JsonObject ?: return@mapNotNull null
            val uri = peer.string("uri")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            Peer(uri = uri, peerPublicKey = peer.string("peer_public_key")?.trim()?.takeIf(String::isNotEmpty))
        }.orEmpty()
        val publicServer = string("public_server_url")?.trim()?.takeIf(String::isNotEmpty)
        return when (networkingMethod) {
            // Standalone nodes never dial peers, matching the core's gen_config.
            2 -> emptyList()
            // Public-server mode uses explicit peers, otherwise the public URL;
            // peer_urls are only considered in Manual mode.
            0 -> explicit.ifEmpty { publicServer?.let { listOf(Peer(it)) }.orEmpty() }
            else -> explicit.ifEmpty { strings("peer_urls").map(::Peer) }
        }
    }

    private fun JsonObject.proxyNetworks(): List<ProxyNetwork> =
        strings("proxy_cidrs").mapNotNull { value ->
            val parts = value.split("->", limit = 2).map(String::trim)
            if (parts[0].isEmpty()) return@mapNotNull null
            ProxyNetwork(
                cidr = parts[0],
                mappedCidr = parts.getOrNull(1)?.takeIf(String::isNotEmpty),
            )
        }

    private fun JsonObject.portForwards(): List<PortForward> =
        array("port_forwards")?.mapNotNull { element ->
            val forward = element as? JsonObject ?: return@mapNotNull null
            val bindIp = forward.string("bind_ip")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val dstIp = forward.string("dst_ip")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val bindPort = forward.int("bind_port") ?: return@mapNotNull null
            val dstPort = forward.int("dst_port") ?: return@mapNotNull null
            val proto = forward.string("proto")?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: "tcp"
            PortForward(
                bindAddr = socketAddr(bindIp, bindPort),
                dstAddr = socketAddr(dstIp, dstPort),
                proto = proto,
            )
        }.orEmpty()

    private fun JsonObject.vpnPortal(): VpnPortal? {
        val portal = this["vpn_portal_config"] as? JsonObject ?: return null
        val clients = portal.array("clients")?.mapNotNull { element ->
            val client = element as? JsonObject ?: return@mapNotNull null
            val name = client.string("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            VpnPortalClient(
                name = name,
                virtualIp = client.string("virtual_ip")?.trim().orEmpty(),
                groups = client.strings("groups"),
            )
        }.orEmpty()
        return VpnPortal(
            wireguardListen = portal.string("wireguard_listen")?.trim()?.ifBlank { "0.0.0.0:11011" } ?: "0.0.0.0:11011",
            wireguardPrivateKey = portal.string("wireguard_private_key")?.trim()?.takeIf(String::isNotEmpty),
            clients = clients,
        )
    }

    private fun JsonObject.secureMode(): SecureMode {
        val secure = this["secure_mode"] as? JsonObject ?: return SecureMode()
        val privateKey = secure.string("local_private_key")?.takeIf(String::isNotEmpty)
        return SecureMode(
            enabled = secure.bool("enabled") ?: (privateKey != null),
            localPrivateKey = privateKey,
            localPublicKey = secure.string("local_public_key")?.takeIf(String::isNotEmpty),
        )
    }

    private fun JsonObject.compressionAlgorithm(): CompressionAlgo =
        when (intOrName("data_compress_algo", mapOf("none" to 1, "zstd" to 2))) {
            2 -> CompressionAlgo.Zstd
            else -> CompressionAlgo.None
        }

    private fun JsonObject.encryptionAlgorithm(): EncryptionAlgorithm =
        when (string("encryption_algorithm")?.trim()?.lowercase()) {
            "xor" -> EncryptionAlgorithm.Xor
            "aes-256-gcm" -> EncryptionAlgorithm.Aes256Gcm
            "chacha20" -> EncryptionAlgorithm.ChaCha20
            else -> EncryptionAlgorithm.AesGcm
        }

    private fun JsonObject.acl(): Acl? {
        val acl = this["acl"] as? JsonObject ?: return null
        val v1 = acl["acl_v1"] as? JsonObject ?: return null
        if (v1.isEmpty()) return null
        val chains = v1.array("chains")?.mapNotNull { element ->
            val chain = element as? JsonObject ?: return@mapNotNull null
            val name = chain.string("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            AclChain(
                name = name,
                chainType = AclChainType.fromValue(
                    chain.intOrName("chain_type", mapOf("inbound" to 1, "outbound" to 2, "forward" to 3))
                        ?: AclChainType.Forward.value,
                ) ?: AclChainType.Forward,
                description = chain.string("description").orEmpty(),
                enabled = chain.bool("enabled") ?: true,
                defaultAction = AclAction.fromValue(
                    chain.intOrName("default_action", mapOf("allow" to 1, "drop" to 2))
                        ?: AclAction.Allow.value,
                ) ?: AclAction.Allow,
                rules = chain.array("rules")?.mapNotNull { ruleElement ->
                    val rule = ruleElement as? JsonObject ?: return@mapNotNull null
                    val ruleName = rule.string("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    AclRule(
                        name = ruleName,
                        description = rule.string("description").orEmpty(),
                        priority = rule.int("priority") ?: 0,
                        enabled = rule.bool("enabled") ?: true,
                        protocol = AclProtocol.fromValue(
                            rule.intOrName(
                                "protocol",
                                mapOf("tcp" to 1, "udp" to 2, "icmp" to 3, "icmpv6" to 4, "any" to 5),
                            ) ?: AclProtocol.Any.value,
                        ) ?: AclProtocol.Any,
                        ports = rule.strings("ports"),
                        sourceIps = rule.strings("source_ips"),
                        destinationIps = rule.strings("destination_ips"),
                        sourcePorts = rule.strings("source_ports"),
                        action = AclAction.fromValue(
                            rule.intOrName("action", mapOf("allow" to 1, "drop" to 2))
                                ?: AclAction.Allow.value,
                        ) ?: AclAction.Allow,
                        rateLimit = rule.int("rate_limit") ?: 0,
                        burstLimit = rule.int("burst_limit") ?: 0,
                        stateful = rule.bool("stateful") ?: false,
                        sourceGroups = rule.strings("source_groups"),
                        destinationGroups = rule.strings("destination_groups"),
                    )
                }.orEmpty(),
            )
        }.orEmpty()
        val group = (v1["group"] as? JsonObject)?.let { groupObject ->
            AclGroup(
                declares = groupObject.array("declares")?.mapNotNull { element ->
                    val declare = element as? JsonObject ?: return@mapNotNull null
                    val groupName = declare.string("group_name")?.trim()?.takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    AclGroupDeclare(
                        groupName = groupName,
                        groupSecret = declare.string("group_secret").orEmpty(),
                    )
                }.orEmpty(),
                members = groupObject.strings("members"),
            )
        } ?: AclGroup()
        return Acl(chains = chains, group = group)
    }

    private fun JsonObject.managedCredentials(): List<ManagedCredential> =
        array("managed_credentials")?.mapNotNull { element ->
            val credential = element as? JsonObject ?: return@mapNotNull null
            val id = credential.string("credential_id")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            ManagedCredential(
                credentialId = id,
                credentialSecret = credential.string("credential_secret").orEmpty(),
                groups = credential.strings("groups"),
                allowRelay = credential.bool("allow_relay") ?: false,
                allowedProxyCidrs = credential.strings("allowed_proxy_cidrs"),
                expiryUnix = credential.long("expiry_unix") ?: 0,
                reusable = credential.bool("reusable") ?: true,
            )
        }.orEmpty()

    private fun socketAddr(host: String, port: Int): String =
        if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    /**
     * Decodes URL-safe base64 without padding. Kept local instead of
     * `java.util.Base64` because that class needs API 26 and the app supports
     * API 24.
     */
    private fun decodeBase64Url(encoded: String): ByteArray? {
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in encoded) {
            val value = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '-' -> 62
                '_' -> 63
                '=' -> continue
                else -> return null
            }
            buffer = ((buffer shl 6) or value) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        // A single trailing base64 character cannot encode a byte.
        if (bits >= 6) return null
        return output.toByteArray()
    }
}
