package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.ConfigServerUrlProblem
import cc.ptoe.easytier.compose.core.configServerHost
import cc.ptoe.easytier.compose.core.configServerUrlProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app validates config-server endpoints before it starts the core client,
 * so these cases mirror the core's own `ConfigServerEndpoint::parse` tests
 * (easytier-core/src/management/full/web_client.rs) plus the practical guards.
 */
class ConfigServerUrlTest {

    @Test
    fun `accepts the endpoint forms the core accepts`() {
        assertNull(configServerUrlProblem("udp://example.com:22020/team"))
        assertNull(configServerUrlProblem("tcp://example.com/team"))
        assertNull(configServerUrlProblem("ws://example.com/team"))
        assertNull(configServerUrlProblem("wss://example.com/team"))
        assertNull(configServerUrlProblem("ring://8b2d4d4e-1b30-4c3b-9b7e-2b3e2f0e5a11/test"))
        // The core's own test: the token is percent-decoded, the path stripped.
        assertNull(configServerUrlProblem("udp://example.com/team%2Ftoken"))
        assertNull(configServerUrlProblem("  wss://example.com/user  "))
    }

    @Test
    fun `rejects a token without a scheme`() {
        // "team%2Ftoken" is relative; the core rejects it as a URL parse error.
        assertEquals(ConfigServerUrlProblem.MALFORMED, configServerUrlProblem("team%2Ftoken"))
        assertEquals(ConfigServerUrlProblem.MALFORMED, configServerUrlProblem("example.com/user"))
    }

    @Test
    fun `rejects an empty token`() {
        assertEquals(ConfigServerUrlProblem.MISSING_TOKEN, configServerUrlProblem("udp://example.com"))
        assertEquals(ConfigServerUrlProblem.MISSING_TOKEN, configServerUrlProblem("udp://example.com/"))
        assertEquals(ConfigServerUrlProblem.MISSING_TOKEN, configServerUrlProblem("wss://example.com/team/"))
    }

    @Test
    fun `rejects an unsupported scheme`() {
        assertEquals(ConfigServerUrlProblem.UNSUPPORTED_SCHEME, configServerUrlProblem("unknown://example.com/team"))
        assertEquals(ConfigServerUrlProblem.UNSUPPORTED_SCHEME, configServerUrlProblem("ftp://example.com/team"))
    }

    @Test
    fun `requires a host`() {
        assertEquals(ConfigServerUrlProblem.MISSING_HOST, configServerUrlProblem("udp:///team"))
        assertEquals(ConfigServerUrlProblem.MISSING_HOST, configServerUrlProblem("wss:///team"))
    }

    @Test
    fun `missing value is reported separately`() {
        assertEquals(ConfigServerUrlProblem.MISSING, configServerUrlProblem(null))
        assertEquals(ConfigServerUrlProblem.MISSING, configServerUrlProblem("   "))
    }

    @Test
    fun `display host never includes credentials or the token`() {
        assertEquals("example.com", configServerHost("udp://user:pass@example.com:22020/secret-token"))
        assertEquals("example.com", configServerHost("wss://example.com/secret-token"))
        assertEquals("", configServerHost(null))
    }
}
