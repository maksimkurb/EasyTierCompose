package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.core.ValidationMessage
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.ProfileKind
import cc.ptoe.easytier.compose.data.TunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web-managed profiles have no locally authored network configuration: they must
 * be accepted without a network name / virtual IP, but only with a valid endpoint
 * and a supported TUN mode. The native parser is stubbed like in the other tests.
 */
class WebManagedProfileValidationTest {

    private val parser = NativeConfigParser { null }

    private fun profile(
        url: String? = "udp://example.com:22020/team",
        tunMode: TunMode = TunMode.VPN_SERVICE,
        cachedToml: String? = null,
    ) = EasyTierProfile(
        id = "profile-1",
        name = "Office",
        networkName = "",
        networkSecret = "",
        virtualIpv4 = null,
        dhcp = false,
        enableMagicDns = false,
        tunMode = tunMode,
        kind = ProfileKind.WEB_MANAGED,
        configServerUrl = url,
        cachedToml = cachedToml,
    )

    private fun message(errors: Map<String, ValidationMessage>, key: String): Int? =
        (errors[key] as? ValidationMessage.Resource)?.id

    @Test
    fun `endpoint only profile is valid`() {
        assertTrue(ProfileValidator(parser).validate(profile()).isEmpty())
    }

    @Test
    fun `endpoint is required and validated`() {
        assertEquals(
            R.string.error_config_server_url_required,
            message(ProfileValidator(parser).validate(profile(url = null)), "configServerUrl"),
        )
        assertEquals(
            R.string.error_config_server_url_scheme,
            message(ProfileValidator(parser).validate(profile(url = "ftp://example.com/team")), "configServerUrl"),
        )
        assertEquals(
            R.string.error_config_server_url_token,
            message(ProfileValidator(parser).validate(profile(url = "udp://example.com")), "configServerUrl"),
        )
    }

    @Test
    fun `name is still required`() {
        val unnamed = profile().copy(name = "")
        assertEquals(
            R.string.error_profile_name_required,
            message(ProfileValidator(parser).validate(unnamed), "name"),
        )
    }

    @Test
    fun `root tun is rejected`() {
        assertEquals(
            R.string.error_web_managed_root_unsupported,
            message(ProfileValidator(parser).validate(profile(tunMode = TunMode.ROOT_TUN)), "tunMode"),
        )
        assertTrue(ProfileValidator(parser).validate(profile(tunMode = TunMode.NO_TUN)).isEmpty())
    }

    @Test
    fun `unreadable cache is reported`() {
        assertEquals(
            R.string.error_web_cached_invalid,
            message(ProfileValidator(parser).validate(profile(cachedToml = "definitely not toml")), "cachedConfig"),
        )
    }
}
