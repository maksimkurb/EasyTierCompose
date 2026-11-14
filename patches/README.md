# Optional patches for EasyTier-Core

Patches in this directory are **not applied by default**. The Gradle task
`buildRustJniLibs` applies them (and reverts them after the build, leaving the
submodule clean) when you ask for them by file name:

```bash
./gradlew :app:assembleRelease -PrustPatches=0001-no-default-stun-servers.patch
```

Several patches can be listed, separated by commas; they are applied in the order given.
The patch list is a task input, so changing it triggers a rebuild of the native library.

| Patch | Effect |
|---|---|
| `0001-no-default-stun-servers.patch` | Empties the compiled-in default STUN server lists (UDP, TCP, IPv6). The core then contacts no STUN server unless the profile lists some. NAT traversal is degraded, see below. |

## 0001-no-default-stun-servers.patch: effect on NAT traversal

STUN is how the core learns (a) its NAT type and (b) its public ip:port as seen from
the internet. Without it:

* NAT type stays `Unknown`; the mapped address is never discovered.
* Two peers that are **both behind NAT** (the common mobile case) lose UDP/TCP hole
  punching and are relayed through a reachable node. This works, but with higher
  latency and the relay node's bandwidth.
* Peers that are directly reachable (public IP, port-forwarded listener, same LAN,
  `tcp://`/`udp://` peer with a public address) still connect directly.
* Peer discovery and the data path do not use STUN, so connectivity itself is unaffected.

If you want NAT traversal *and* no default third-party servers, keep the patch off
and list your own STUN servers in the profile (Editor, "STUN & Whitelists").
The app omits empty lists, so an empty list in the profile means "use the core defaults".
