# AGENTS.md

本文件为在此仓库中工作的 AI 代理提供指引。项目所有代码注释与提交信息以英文为主，本说明保留关键技术术语原文。

## 项目概览

EasyTierCompose 是一个单 Activity 的 Jetpack Compose Android 应用，封装 [EasyTier](https://github.com/EasyTier/EasyTier)（Rust + Tokio 实现的去中心化 VPN）核心。Rust 核心由 Gradle 任务 `buildRustJniLibs` 从 `EasyTier-Core` submodule 构建为 `libeasytier_android_jni.so`（见下文 *Native library build*），应用通过 JNI 调用它。仓库中**不提交**任何预构建的 `.so`。

* 应用包名 / namespace：`cc.ptoe.easytier.compose`

* 应用显示名：EasyTier

* 根工程名（settings.gradle.kts）：`EasyTier`

* 单模块：`:app`

* EasyTier-Core 为 git submodule（`EasyTier-Core/`，指向 <https://github.com/EasyTier/EasyTier.git>；固定提交 `81487c3`，该提交位于上游 `main` 历史中。此前文档中的 `ECSDevs/EasyTier` 不存在，已更正），由 `buildRustJniLibs` 任务构建

## 构建工具链

| 项                                | 版本 / 配置                                                                |
| -------------------------------- | ---------------------------------------------------------------------- |
| AGP                              | 9.4.1                                                                  |
| Kotlin                           | 2.2.10                                                                 |
| Compose BOM                      | 2026.02.01                                                             |
| Gradle                           | 9.7.1（wrapper）                                                         |
| Gradle Daemon JVM                | 21（由 `gradle/gradle-daemon-jvm.properties` 固定，通过 foojay-resolver 自动下载） |
| Java source/target compatibility | 11                                                                     |
| compileSdk                       | 37                                                                     |
| minSdk                           | 24                                                                     |
| targetSdk                        | 36                                                                     |
| NDK（CMake）                       | 30.0.16248370 / 4.1.2                                                  |
| ABI                              | arm64-v8a, armeabi-v7a, x86\_64                                        |

版本统一在 [gradle/libs.versions.toml](gradle/libs.versions.toml) 中管理。配置缓存已启用（`org.gradle.configuration-cache=true`）。release 构建关闭了 optimization（`enable = false`）。

## 常用命令

```bash
# 构建
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 单元测试（TomlConfigBuilder + ProfileValidator）
./gradlew test
./gradlew :app:testDebugUnitTest

# 安装到设备
./gradlew :app:installDebug

# 查看 dependency tree
./gradlew :app:dependencies
```

`libroot_tun_jni.so` 由 `externalNativeBuild`（CMake）在构建时自动从 `app/src/main/cpp/root_tun_jni.cpp` 编译，无需手动操作。

`libeasytier_android_jni.so` 由 Gradle 任务 `buildRustJniLibs` 构建（取代了原先的 `update-easytier-jni.ps1` 与手动拷贝；**旧规则“不要将其纳入 Gradle 构建”已废止**）。详见下一节。

## Native library build (English)

`libeasytier_android_jni.so` is built from the `EasyTier-Core` submodule by the Gradle task
`:app:buildRustJniLibs` (`buildSrc/src/main/kotlin/cc/ptoe/easytier/gradle/BuildRustJniLibsTask.kt`,
registered in `app/build.gradle.kts`). Never commit a built `.so`; `app/src/main/jniLibs/**/libeasytier_android_jni.so` is git-ignored.

* **Flow**: `cargo ndk --target <abi> build --release --locked --package easytier-android-jni` run in
  `EasyTier-Core` for `arm64-v8a`, `armeabi-v7a`, `x86_64`. `easytier-ffi` is a path dependency of the JNI
  crate and is compiled as part of it. Output: `app/build/generated/rustJniLibs/<abi>/libeasytier_android_jni.so`
  (plus `app/build/generated/rustJniLibs-BUILD-INFO.txt` with the core commit, rustc, cargo-ndk and NDK versions).
  The directory is registered per variant via `androidComponents.onVariants { variant.sources.jniLibs }`, so
  `merge*JniLibResources` / `assemble*` depend on it. `CARGO_TARGET_DIR` is `<root>/build/rust-target` (the submodule stays clean).
* **NDK**: the task uses the NDK that AGP resolves from `android.ndkVersion` and exports it as `ANDROID_NDK_HOME`.
  It also sets the dashed `BINDGEN_EXTRA_CLANG_ARGS_<triple>` variable with a *versioned* `--target` (kcp-sys' bindgen
  fails with "Unversioned target triples are not supported" otherwise) and `LIBCLANG_PATH` to the NDK's libclang.
* **Prerequisites are checked, never installed**: Rust toolchain from `EasyTier-Core/rust-toolchain.toml` (currently 1.95;
  `RUSTUP_AUTO_INSTALL=0` is set so rustup cannot download one silently), the three `*-linux-android` rustup targets,
  `cargo-ndk`, `protoc`. A missing piece fails the build with the command to run.
* **Incremental**: inputs are the submodule commit (`git rev-parse HEAD:EasyTier-Core`), the core's sources
  (excluding `target/`, docs, GUI, web), the ABI list, the NDK path and the patch list. Use `--rerun-tasks` after upgrading rustc or cargo-ndk.
* **Gradle properties**: `-PrustAbis=arm64-v8a,x86_64` (subset of ABIs); `-PskipRustBuild -PrustJniLibsDir=<dir>` (use a
  prebuilt `<dir>/<abi>/libeasytier_android_jni.so`, no Cargo; CI uses this with the output of `build-native.yml`);
  `-PrustPatches=<name>[,<name>]` (apply `patches/<name>` to the core for this build and revert afterwards, see `patches/README.md`).
* **F-Droid / offline**: no committed binaries except `gradle/wrapper/gradle-wrapper.jar`. The Gradle build needs no network
  beyond what Cargo downloads (crates and git dependencies pinned by the core's `Cargo.lock`, `--locked`).
* **JNI wrapper**: if native method signatures change in a new core version, also sync
  `EasyTier-Core/easytier-contrib/easytier-android-jni/kotlin/com/easytier/jni/` into `app/src/main/java/com/easytier/jni/`.

## Config-server (web-managed) mode (English)

EasyTier-web dashboards ("config servers") can own a device's network configuration. The app implements this as a
second profile kind (`ProfileKind.WEB_MANAGED`) instead of a second sync engine: **the core already does the work**.
Verified against `EasyTier-Core` @ `81487c3`:

* `easytier-core/src/management/full/web_client.rs` — `WebClient` connects to the endpoint, retries every
  `RETRY_INTERVAL = 1s`, heartbeats every second, and applies server-pushed `NetworkConfig`s by running/removing
  network instances *inside the core*. `ConfigServerEndpoint::parse` defines the accepted endpoints: an absolute URL
  with a supported tunnel scheme (`tcp`, `udp`, `ws`, `wss`, `wg`, `quic`, `faketcp`, `unix`, `http`, `https`,
  `ring`, `txt`, `srv` with the JNI feature set) whose **last path segment is a non-empty user token**
  (percent-decoded). For non-WebSocket schemes the path is stripped from the connect URL; the token is sent in the
  heartbeat. There is no default server.
* `easytier-contrib/easytier-ffi/src/config_server.rs` — `start_config_server_client` / `stop_config_server_client` /
  `is_config_server_client_connected`, the process-wide singleton and `ManagedConfigServerClientHooks`
  (`manages_remote_config_instances() = true`). Stopping the client deletes the instances it manages; there is **no
  offline cache** and no `ConfigFileStorage` usage in this path (the JNI manager has no config dir, so nothing is
  persisted natively).
* `easytier-contrib/easytier-android-jni/src/lib.rs` + `config_server_api.rs` — the JNI symbols and the
  `ConfigServerEventCallback` object (`onEvent(String)`, called on a core worker thread; JNI calls from inside the
  callback are rejected by the FFI).
* Events are JSON: `{"event":"run_network_instance"|"delete_network_instance","success":true,"instance_id":"…",
  "instance_name":"…","network_name":"…"}`. `instance_name` is the config's `network_name` (`NetworkConfig::gen_config`
  calls `set_inst_name(network_name)`), not the app profile id.
* The delivered configuration can be read back with the generic JSON-RPC bridge: service
  `api.config.ConfigRpcService`, method `get_config`, payload
  `{"instance":{"instance_selector":{"name":"<instance_name>"}}}` → `toml_config`. `collectNetworkInfos` only
  reports runtime state (virtual IP, routes, peers). `api.manage.WebClientService` is explicitly blocked from the
  JNI bridge.
* The managed instance uses the same `NativeInstanceFactory` as locally started instances, so `setTunFd(instance_name,
  fd)` attaches a `VpnService`/`RootTun` fd to it like any other instance. The mobile TUN host waits for the fd on a
  channel, so attaching after the server's `run_network_instance` event is the intended order.

### What the app adds (and nothing more)

* `core/WebManagedConfig.kt` — pure logic: endpoint validation mirroring the core parser, event parsing, the
  `GetConfig` payload/response helpers, "is this config replayable", the live-vs-cached-vs-unavailable decision,
  the cache update rule (failed syncs never touch the cache) and the cached-profile replay. All unit-tested.
* `core/WebConfigServerBridge.kt` — the only JNI touch points (`start`/`stop`/`isConnected`/`configToml`/`retainOnly`).
* `core/EasyTierRuntimeCoordinator.kt` — a per-connection `WebSession` that consumes the callback events through a
  channel (never touching JNI on the callback thread), caches every applied config through
  `ProfileRepository.saveWebCache` (cache fields only; other edits are preserved), and drives the session:
  1. Connect waits up to `WEB_FIRST_CONFIG_TIMEOUT_MILLIS = 12s` for the first applied config. Live → attach the TUN
     to the core-managed instance and poll `collectNetworkInfos(instance_name)`.
  2. Otherwise the cached TOML is replayed as an ordinary local instance (`replayWebConfig`: imported from the
     cached TOML, `id = profile.id`, the profile's TUN mode and hostname, global overrides applied; fidelity is
     bounded by `TomlProfileImporter`/`TomlConfigBuilder`, so fields the app model does not carry, e.g.
     `peer_public_key`, `netns` or `source`, are not round-tripped). The client is
     **kept running**: the core retries in the background, and when it eventually delivers a config the session is
     promoted to live (`vpn.startManaged` reattaches the TUN, then `retainNetworkInstance(arrayOf(primary))` drops the
     cached instance). No WorkManager, timers or extra services.
  3. No cache and no config → `error_web_no_cache`.
* `RuntimeStatus.web: WebSessionStatus` carries the server state (from `isConfigServerClientConnected`, sampled when
  a status is published), the origin (`LIVE`/`CACHED`) and the last sync time; the dashboard renders it and a
  non-blocking "using cached configuration from …" notice.
* `ProfileRepository.machineId()` — a random UUID per install, persisted in the profiles DataStore and kept across a
  reset; it is the `machine_id` the server uses to recognise the device.

### Limitations

* **Root TUN is rejected** for web-managed profiles (`ProfileValidator` and the Settings switch): the core's
  config-server client runs in the app process and cannot manage instances inside the libsu root daemon. VPN Service
  and No TUN work.
* One server can deliver several networks; the app tunnels the **first applied instance only** and shows a notice for
  the others. Promotion keeps only that instance (`retainNetworkInstance`), so extra managed instances may be
  restarted by the server later.
* A `no_tun = true` flag inside a server-delivered config is not re-interpreted by the app: the profile's TUN mode
  decides whether a `VpnService` is established, while the core would leave the attached fd unused. Keep `no_tun`
  off for configs used by this app.
* The instance name the server assigns is used for `setTunFd`/`collectNetworkInfos`; a delivered config without a
  usable `[network_identity].network_name` cannot be imported, so it is neither cached nor tunneled (the client
  still runs and the session stays on the previous cache).
* No live server was available while developing this: the event/RPC shapes above come from the pinned core sources,
  and only the pure logic (URL rules, event parsing, cache policy, replay, JSON compatibility) is covered by tests.
  The end-to-end path (start client → event → `GetConfig` → TUN attach → promotion) has **not** been exercised on a
  device against a real EasyTier-web server.

## Localization

Default strings are English (`values/strings.xml`); Russian is `values-ru/strings.xml` and Chinese `values-zh-rCN/strings.xml` (both complete).
Add every new string to all three files (strings referenced from the manifest, e.g. `fgs_special_use_subtype`, must be `translatable="false"`). `LocalizationTest` fails when a key,
a plural quantity (`one/few/many/other` for ru, `other` for zh) or a format placeholder is missing or mismatched, or when
`res/xml/locales_config.xml` disagrees with the `values-*` directories. Russian terminology: peer = «узел», subnet = «подсеть»,
profile = «профиль», relay = «ретранслятор», NAT traversal = «обход NAT»; protocol names, config keys and the product name stay untranslated.
When adding a language update `locales_config.xml` and `androidResources.localeFilters` in `app/build.gradle.kts`.

## 代码架构

包根：`cc.ptoe.easytier.compose`（`app/src/main/java/cc/ptoe/easytier/compose/`）

### 入口

* [MainActivity.kt](app/src/main/java/cc/ptoe/easytier/compose/MainActivity.kt) — 单 Activity，
  `enableEdgeToEdge()`，通过 `ViewModelProvider.Factory` 手动注入 `ProfileRepository`、
  `EasyTierRuntimeCoordinator` 与 `GlobalSettingsRepository`。**明确不自动连接**（root/consent
  流程必须显式触发）。

### core 层

* [EasyTierJni.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierJni.kt) — 应用拥有的 JNI
  门面（facade）。**应用代码统一使用此类，禁止直接调用** **`com.easytier.jni.EasyTierJNI`。**

* [EasyTierRuntimeCoordinator.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierRuntimeCoordinator.kt) —
  运行时编排器。`SupervisorJob + Dispatchers.IO` 协程域，`Mutex` 串行化 start/stop，持有
  `VpnTunTransport` 与 `RootTunTransport`。`start(profile, globalSettings)` 是**唯一的启动入口**（ViewModel 的
  Connect 与 `BootCompletedReceiver` 都走这里）：先在 mutex 之外调用 `autoRefreshBeforeStart`（必要时重新读取
  URL 来源并落盘，失败静默回退到已存配置），再用 `withDeviceHostnameIfBlank` 从 Android 设备名补全 hostname，
  经 `ProfileValidator` + `TomlConfigBuilder` 生成 TOML，按 `tunMode` 分派到 VPN / Root 实现；轮询 `collectNetworkInfos` 解析虚拟
  IPv4、`proxy_cidrs` 远端路由、hostname、NAT 类型与 `peer_route_pairs` Peer 列表（VPN\_SERVICE 直接在
  app 进程轮询；ROOT\_TUN 通过 AIDL 收集 `RootTunTransport.status` flow）。

  **Web-managed（配置服务器）会话**走单独分支 `startWebManagedLocked`：`WebSession` 保存一次连接的状态
  （主实例名、已应用 TOML、缓存时间、是否使用缓存、额外网络数），事件经 `Channel` 从 Rust 回调线程转交协程；
  `WebConfigServerBridge` 封装 JNI。首个配置等待上限 12s：收到则把 TUN 挂到核心托管的实例并按
  `instance_name` 轮询；否则回放缓存 TOML 作为普通本地实例，同时保留客户端在后台重连，稍后收到配置时
  `promoteWebSession` 切换为 live 并删除缓存实例。详见英文段落 *Config-server (web-managed) mode*。

* [WebManagedConfig.kt](app/src/main/java/cc/ptoe/easytier/compose/core/WebManagedConfig.kt) — 纯函数：
  配置服务器 URL 校验（复刻核心 `ConfigServerEndpoint::parse` 语义）、事件 JSON 解析、
  `ConfigRpcService/GetConfig` payload/响应、缓存可用性判断、live/cached/unavailable 决策、
  「失败不覆盖缓存」规则与缓存回放（`replayWebConfig`）。全部由单元测试覆盖。

* [WebConfigServerBridge.kt](app/src/main/java/cc/ptoe/easytier/compose/core/WebConfigServerBridge.kt) —
  `WebConfigServerBridge` 接口 + `JniWebConfigServerBridge`：start/stop/isConnected/configToml/retainOnly，
  是此功能唯一的 JNI 触点。

* [ProfileValidator.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ProfileValidator.kt) —
  `ProfileValidator`：字段校验（IPv4/IPv6 CIDR、MTU、peers、proxy networks、port forwards、VPN
  portal、multiThreadCount、bps 限制等）+ 生成 TOML 的原生 `parseConfig` 校验，接受 `GlobalSettings`
  作为第二参数。私有扩展函数（`isValidIpv4Cidr` / `isValidIpv6Cidr` / `isValidSocketAddr` /
  `isValidListenerUrl` 等）位于文件末尾。

* [TomlConfigBuilder.kt](app/src/main/java/cc/ptoe/easytier/compose/core/TomlConfigBuilder.kt) —
  `TomlConfigBuilder`（object）：结构化 profile + GlobalSettings → TOML，并提供 `rootTunSpec()` 生成
  `RootTunSpec`（含 `noTun` 标志）。ROOT\_TUN 模式下（含 no\_tun）输出 `bind_device = false` +
  `socket_mark = 0x20000`（`ROOT_TUN_SOCKET_MARK`）以绕过 VpnService/mihomo TUN。\*\*已移除高级 TOML 透传路径
  \*\*，所有配置均通过结构化表单生成。**根级标量（`socks5_proxy`** **/** **`credential_file`）必须位于首个
  `[[...]]`** **表头之前**——TOML 中无表头的裸键归属当前打开的（数组）表，否则会被嵌套进 `[[port_forward]]` /
  ACL 子表中导致功能失效。

* [NativeConfigParser.kt](app/src/main/java/cc/ptoe/easytier/compose/core/NativeConfigParser.kt) —
  `NativeConfigParser`（fun interface）+ `EasyTierNativeConfigParser`（object 默认实现，调用
  `EasyTierJni.parseConfig` / `getLastError`）。测试通过注入桩绕过真实 JNI。

* [TomlProfileImporter.kt](app/src/main/java/cc/ptoe/easytier/compose/core/TomlProfileImporter.kt) —
  `TomlProfileImporter`（object，依赖 `org.tomlj:tomlj`）+ `TomlImportResult`（Success/Failure）。
  把 EasyTier TOML 反序列化为 `EasyTierProfile`（与 `TomlConfigBuilder` 互为逆映射，未知键忽略）。
  导入字段映射：顶层 `ipv4/ipv6/dhcp/listeners/mapped_listeners/exit_nodes/routes/各种 server/whitelist/
  socks5_proxy/credential_file`、`[network_identity]`、`[[peer]]`（含 `peer_public_key`）、
  `[[proxy_network]]`、`[[port_forward]]`、`[vpn_portal_config]`、`[secure_mode]`、`[acl.acl_v1]`、
  `[[managed_credentials]]`、`[flags]`（全部字段）。不可逆字段取合理默认：`id` = 新 UUID，原
  `instance_name` 落到 `name`；`tunMode` 推断规则——`no_tun=true` → NO\_TUN，出现 `socket_mark` →
  ROOT\_TUN（仅 Root 模式会写该字段），否则 VPN\_SERVICE。

* [EasyTierWebLink.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierWebLink.kt) —
  `EasyTierWebLink`（object）+ `WebLinkResult`。解码 EasyTier 配置分享链接
  `https://easytier.cn/comp_cfg?cfg=...`：URL-safe base64 → zlib → `api.manage.NetworkConfig`
  proto JSON（顶层数字键按 `FIELD_NAMES` 映射回字段名，同时接受命名字段），全程本地处理、**不发起网络请求**。
  链接的 `name` 查询参数优先作为 profile 名；`no_tun` / `socket_mark` 推断 `tunMode`。未知字段忽略。

* [ProfileLinkSource.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ProfileLinkSource.kt) —
  `ProfileLinkSource`（object）+ `ProfileLinkResult`，导入 URL 的统一入口：分享链接走
  `EasyTierWebLink`（无网络）；`http(s)` URL 经 `fetchConfigText` 抓取后交给 `TomlProfileImporter`；
  `udp/tcp/ws/wss/ring` 配置服务器 URL 会返回专门提示（不是可导入的配置）。成功时写入
  `sourceUrl` 与 `sourceSyncedAt`（可注入 `now`，便于测试）。`EasyTierProfile.withImportedConfig` 用于刷新：
  保留 id / 显示名 / `tunMode` / 设备级选项（`tunDeviceName`、`startOnBoot`、`mtu`、线程数、bps、
  `socketMark`）、`sourceUrl` 与 `autoRefresh`；`sourceSyncedAt` 取自新读取的配置（记录读取时刻）。

* [ProfileAutoRefresh.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ProfileAutoRefresh.kt) —
  纯策略 + 纯函数：`shouldAutoRefresh(profile, nowMillis, intervalMillis = 30 min)` 判断「有 `sourceUrl`、
  `autoRefresh` 开启且（从未同步或 `sourceSyncedAt` 超过 30 分钟）」；`autoRefreshProfile(profile, now,
  load, validate)` 调用 `ProfileLinkSource.load`，成功则 `withImportedConfig` + 写入 `sourceSyncedAt` 并交给
  启动同款校验，任何失败（离线/超时/HTTP/解析/config-server/校验失败）返回 `Failure(host, reason)`，
  **绝不修改**传入的 profile。日志只记录 host，不写带查询串的完整 URL。

* [ConfigTextReader.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ConfigTextReader.kt) —
  `ConfigText` + `readLimitedConfigText` / `fetchConfigText`：1 MB 上限、BOM（UTF-8/UTF-16）解码、
  `HttpURLConnection`（默认 10 s，自动刷新传 5 s 连接/读取超时；仅 http/https）。文件导入与 URL 导入共用。

* [NetworkInfo.kt](app/src/main/java/cc/ptoe/easytier/compose/core/NetworkInfo.kt) —
  `collectNetworkInfos` 返回 JSON 的解析逻辑（从原 `EasyTierRuntimeCoordinator.kt` 抽出）：
  `NetworkInfo` data class、`String.networkInfo(profileId)` 顶层解析、`peerRoutePair()` 映射
  `RuntimePeer`、`ipv4InetToCidr`（u32 big-endian → 点分十进制）、`natTypeName`（字符串/数字 0–9 统一）、
  `normalizeTunnelType`（取 scheme）、`normalizeCidr`（裸 IP 补 /32）、
  `EasyTierProfile.withDeviceHostnameIfBlank`（从 `Settings.Global.DEVICE_NAME` 补全 hostname）。

### data 层

* [EasyTierModels.kt](app/src/main/java/cc/ptoe/easytier/compose/data/EasyTierModels.kt) —
  `EasyTierProfile`（`@Serializable`，含 \~50 个字段：基础、peers、listeners、proxy networks、port
  forwards、VPN portal、secure mode、STUN/whitelists、KCP/QUIC proxy flags、bps 限制等；`sourceUrl`
  记录导入来源，`sourceSyncedAt` 记录上次成功同步时刻，`autoRefresh`（默认 true）控制连接前是否自动重读；
  `kind`（`ProfileKind.STATIC`/`WEB_MANAGED`，默认 STATIC）、`configServerUrl`、`cachedToml`/`cachedAt`
  用于配置服务器模式，旧 JSON 反序列化时全部取默认值）、
  `GlobalSettings`（
  `tunDeviceName` / `noTun` / `startOnBoot` / `mtu` / `multiThread` / `multiThreadCount` / bps 限制）、
  `TunMode` / `CompressionAlgo` / `EncryptionAlgorithm` 枚举、`Peer` / `ProxyNetwork` /
  `PortForward` / `VpnPortal` / `SecureMode` 子结构、`RuntimePeer`（含 `connectionType` /
  `tunnelProtos` / `lossRate` / `natType` / `cost`）、`RuntimeState`、`RuntimeStatus`（含 `hostname` /
  `natType` / `peers`）。

* [ProfileRepository.kt](app/src/main/java/cc/ptoe/easytier/compose/data/ProfileRepository.kt) —
  `ProfileRepository`：DataStore Preferences 持久化（store 名 `easytier_profiles`，key `profiles_v1` +
  `selected_profile_id`），JSON 序列化，含损坏数据自动备份恢复逻辑（`profiles_v1_corrupt`）；提供
  `newProfile` / `save` / `delete` / `select` / `reset`。另有 `machineId()`（配置服务器用来识别设备的
  随机 UUID，首次使用时生成并持久化，`reset()` 不清除）与 `saveWebCache(profileId, toml, syncedAt)`
  （只更新 `cachedToml`/`cachedAt`，不覆盖其他字段；仅成功同步时调用）。`GlobalSettingsRepository`：独立 DataStore（
  `easytier_global_settings`），持久化 TUN 设备名 / `no_tun` 等全局设置。

### transport 层

* [RuntimeTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/RuntimeTransport.kt) —
  `RuntimeTransport` 接口（`start(profile, toml, globalSettings)`）与 `RuntimeEffect` sealed
  interface（当前仅 `RequestVpnPermission`）。

* `transport/vpn/`：

  * [VpnTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/VpnTunTransport.kt) —
    VPN 权限申请流，处理 pending start；`establishWhenResolved` 在虚拟 IPv4 解析后启动
    `EasyTierVpnService` 前台服务。

  * [EasyTierVpnService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/EasyTierVpnService.kt) —
    `VpnService` 前台服务（`foregroundServiceType="specialUse"`），通过 `Builder()` 建立 tun，调用
    `EasyTierJni.setTunFd`。

* `transport/root/`：

  * [RootTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunTransport.kt) —
    通过 libsu `RootService.bind` IPC 连接 root 进程，启动后持续轮询 `getStatus()` 并解析
    `peersJson` 为 `List<RuntimePeer>`。

  * [EasyTierRootService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/EasyTierRootService.kt) —
    `RootService`，在 root 进程内运行 EasyTier + 创建真实 `easytier0` 接口，轮询 DHCP；RUNNING 后持续轮询
    `collectNetworkInfos` 更新 Peer 列表。聚焦 Service 生命周期 + Binder + EasyTier 启停，路由与 DNS
    委托给下方两个 manager。通过 `RemoteCallbackList<IRootStatusCallback>` push 状态更新（替代轮询
    `getStatus()`）。

  * [RootTunRouteManager.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunRouteManager.kt) —
    `internal` 类，管理 TUN 路由与 `ip rule`：`syncTunRoutes(cidr, runtimeRoutes, spec)` 计算路由集（虚拟
    IP 子网 + 远端 `proxy_cidrs` + manual routes + Magic DNS 路由，移除本地 `spec.proxyCidrs`
    ）并增量同步；`cleanupRoutesAndRules()` 幂等清理。ip rule 优先级 5000（先于 Android 的 10000+）。

  * [SystemDnsManager.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/SystemDnsManager.kt) —
    `internal` 类，Magic DNS 切换：`enableMagicDns(fakeIp)` 通过 `settings put global dns1/dns2`
    切换系统 DNS 并保存原值；`restore()` 幂等恢复。常量 `MAGIC_DNS_FAKE_IP = "100.100.100.101"`。

  * [RootTunNative.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunNative.kt) —
    加载 `libroot_tun_jni`，提供 `create` / `syncRoutes` / `destroy`。

  * [RootModels.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootModels.kt) —
    `RootTunSpec`、`RootRuntimeStatus`（`Parcelable`，AIDL 载荷）。

### AIDL

位于 `app/src/main/aidl/cc/ptoe/easytier/compose/transport/root/`：

* `IEasyTierRootService.aidl` — `start(profileId, toml, spec)`、`stop()`、`getStatus()`、
  `registerStatusCallback(cb)` / `unregisterStatusCallback(cb)`（push-based 状态更新）

* `IRootStatusCallback.aidl` — `onStatusUpdated(status)` 回调接口

* `RootTunSpec.aidl`、`RootRuntimeStatus.aidl` — parcelable 声明

### ui 层

* [EasyTierApp.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierApp.kt) — Compose 根。五个
  `Destination`（Dashboard / Profiles / Peers / Settings / Editor），自适应布局：`screenWidthDp >= 840`
  用 `NavigationRail`，否则用 `NavigationBar`（Editor 屏幕隐藏底部栏）。Editor 屏幕支持 predictive back
  手势（`PredictiveBackHandler` + `Animatable` 平移/缩放/淡出）。仅保留顶层导航逻辑，屏幕与组件已拆分到
  `ui/screens/` 与 `ui/components/` 子包（所有内部 Composable 为 `internal` 可见性）。

* [EasyTierViewModel.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierViewModel.kt) —
  `combine` 7 个 flow（profiles、selectedProfileId、本地
  selectedId、draft、coordinator.status、errors、globalSettings）聚合为 `EasyTierUiState`，
  `WhileSubscribed(5_000)`。提供 `selectProfile` / `beginCreate` / `beginEdit` / `discardDraft` /
  `updateDraft` / `saveDraft` / `delete` / `updateTunMode` / `updateGlobalSettings` / `connect` /
  `disconnect` / `onVpnPermissionResult` / `resetProfiles`。

* `ui/components/` — 跨屏幕复用的 `internal` Composable：

  * [FormComponents.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/FormComponents.kt) —
    `SectionCard`、`FormField`、`SwitchRow`、`ChoiceRow`（ExposedDropdownMenu）、`ListField` +
    `ListEditorDialog`（通用字符串列表编辑器）。

  * [EditorListFields.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/EditorListFields.kt) —
    `PeerListField` + `PeerListEditorDialog`、`ProxyNetworkListField` + `ProxyNetworkEditorDialog`、
    `PortForwardListField` + `PortForwardEditorDialog`（强类型列表字段 + 编辑对话框）。

  * [SettingsComponents.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/SettingsComponents.kt) —
    `SettingsGroup`、`SettingsItem`、`notificationsPermissionGranted(context)` 工具函数。

* `ui/screens/` — 各 `Destination` 屏幕（`internal` Composable）：

  * [DashboardScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/DashboardScreen.kt) —
    `DashboardScreen` + `StatusCard` + `StatusDetailsGroup`（网络名 / 虚拟 IP / hostname / NAT
    类型 / 状态）+ `ErrorBanner`。

  * [ProfilesScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/ProfilesScreen.kt) —
    profile 列表 + 删除确认对话框 + 导出（`CreateDocument` 写
    `TomlConfigBuilder.build(profile, globalSettings)`，web-managed 不导出）。FAB 弹出四选添加对话框：手动添加 /
    连接配置服务器（URL 按核心解析规则校验后创建 `WEB_MANAGED` 草稿）/ 导入 TOML 文件（`OpenDocument`，SAF 读取）/
    从链接导入（分享链接本地解码；http(s) URL 经 `ProfileLinkSource` 抓取 TOML，上限 1MB）；成功后回填为
    编辑器草稿并记录 `sourceUrl`，失败弹错误对话框。web-managed 卡片的摘要显示缓存网络名或服务器 host（不含 token）。

  * [PeersScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/PeersScreen.kt) —
    `PeersScreen` 列出 `RuntimePeer`，`PeerDetailsDialog` 展示延迟 / 隧道 / 丢包率等详情。

  * [ProfileEditorScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/ProfileEditorScreen.kt) —
    按分区组织全部配置（General / Network & Peers / Routing / IPv6 Public Address / Port Forwards /
    VPN Portal (WireGuard) / Secure Mode / STUN & Whitelists / Flags — General / Flags — P2P /
    Flags — KCP Proxy / Flags — QUIC Proxy）。若 profile 带 `sourceUrl`，顶部额外显示「Imported from link」
    分区：展示来源 URL、**Refresh from link** 按钮（运行中禁用；刷新结果先进入草稿，保存时才落盘）、
    **Auto-refresh on connect** 开关与只读的「Last synced」本地化时间（`DateUtils.formatDateTime`）。
    **web-managed profile 编辑器只显示** General（名称 / hostname）与「Config server」分区：URL、提示、
    **Sync now**（`EasyTierRuntimeCoordinator.syncWebManagedConfig`，运行中禁用）、上次同步时间与只读的缓存摘要
    （网络名 / 虚拟 IP 或 DHCP）；修改 URL 会清空草稿里的缓存（不同服务器的配置不可混用）。其余分区全部隐藏。

  * [DashboardScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/DashboardScreen.kt) —
    状态卡 / 详情 / WireGuard portal 卡；web-managed 额外显示 `WebConfigStatusGroup`（服务器状态、live/cached、
    上次同步）与使用缓存时的非阻塞提示 `CachedConfigNotice`，多网络时显示 `web_extra_networks` 复数提示。

  * [SettingsScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/SettingsScreen.kt) —
    TUN 模式切换（web-managed 不提供 Root 选项）、**Global overrides**（TUN 设备名 / `no_tun` / Start on boot）、
    通知授权与 Reset。

* `ui/theme/` — `EasyTierTheme`（亮/暗双色方案 + 自定义 `ExpressiveShapes`）、`Color.kt`、`Type.kt`。

### 原生符号所有者

* [com/easytier/jni/EasyTierJNI.kt](app/src/main/java/com/easytier/jni/EasyTierJNI.kt) —
  `System.loadLibrary("easytier_android_jni")` 的所有者，仅被 `core.EasyTierJni` 引用。

## 两种 TUN 模式

| 维度        | VPN\_SERVICE          | ROOT\_TUN                                            |
| --------- | --------------------- | ---------------------------------------------------- |
| 实现        | `VpnService.Builder`  | `/dev/net/tun` + netlink（root 进程）                    |
| 权限        | 系统 VPN 授权弹窗           | root（libsu RootService IPC）                          |
| 前台服务      | `specialUse`          | 否（root 进程内运行）                                        |
| Magic DNS | 支持（`100.100.100.101`） | 支持（root 进程内运行 + `settings put global dns1` 切换系统 DNS） |
| 设备名       | Android VPN（虚拟）       | `easytier0`（真实接口）                                    |

切换 TUN 模式在 Settings 页通过 Switch 完成（UI 显示为 "Root" / "VPN Service"）；运行中禁止切换。**No
TUN 模式下该 Switch 常驻显示且有意义**：No TUN 不能和 VpnService / 其他代理同时工作，此时 Root
模式将核心运行在 root daemon 进程（不创建 TUN），配合 `socket_mark` 确保核心流量走物理网卡，不被
VpnService/其他代理的 TUN 劫持；VPN\_SERVICE 侧的 No TUN 仍在 app 进程内运行核心（无 TUN）。

## EasyTier TOML 配置约定

`TomlConfigBuilder.build(profile, globalSettings)` 生成的结构化 TOML 关键字段：

* 顶层：`instance_name`（= profile.id）、`hostname`、`dhcp`、`ipv4`、`ipv6`、`listeners`、`mapped_listeners`、
  `exit_nodes`、`routes`（manual routes）、`stun_servers`、`stun_servers_v6`、`tcp_whitelist`、
  `udp_whitelist`、`ipv6_public_addr_provider` / `ipv6_public_addr_auto` / `ipv6_public_addr_prefix`。

* `[network_identity]` — `network_name` / `network_secret`。

* `[[peer]]` — `uri`（peer URI 来自 `Peer.uri`，当前 `peerPublicKey` 仅存于模型，未写入 TOML）。

* `[[proxy_network]]` — `cidr` / `mapped_cidr`（可选）/ `allow`（tcp/udp/icmp 列表）。

* `[[port_forward]]` — `bind_addr` / `dst_addr` / `proto`（tcp/udp）。

* `[vpn_portal_config]` — `client_cidr` / `wireguard_listen`（仅 `vpnPortal != null` 时输出）。

* `[secure_mode]` — `enabled` / `local_private_key` / `local_public_key`（仅 `secureMode.enabled`
  时输出）。

* `[flags]` — `default_protocol`、`dev_name`（来自 `globalSettings.tunDeviceName`）、
  `enable_encryption`、`enable_ipv6`、`mtu`、`latency_first`、`enable_exit_node`、`no_tun`（来自
  `globalSettings.noTun`）、`use_smoltcp=false`、`relay_network_whitelist`、`disable_p2p` / `p2p_only` /
  `lazy_p2p` / `relay_all_peer_rpc`、各 hole punching / UPnP 开关、`multi_thread` /
  `multi_thread_count`、`data_compress_algo`（枚举名：None/Zstd）、`bind_device`（VPN\_SERVICE =
  profile.bindDevice；ROOT\_TUN 强制 false）、`socket_mark`（仅 ROOT\_TUN（含 no\_tun），= `0x20000` /
  `ROOT_TUN_SOCKET_MARK`，使 Android 策略路由绕过 VpnService TUN 走物理接口）、KCP proxy 系列开关、
  `proxy_forward_by_system`、`accept_dns`（= `enableMagicDns`）、`private_mode`、QUIC proxy 系列开关、
  `foreign_relay_bps_limit` / `instance_recv_bps_limit`、`encryption_algorithm`（xor / aes-gcm /
  aes-256-gcm / chacha20）、`tld_dns_zone`、`disable_relay_data`、`enable_udp_broadcast_relay`。

约束与说明：

* MTU 默认 1380，有效区间 576..9000。

* `accept_dns` 在开启 Magic DNS 时为 true（VPN\_SERVICE 与 ROOT\_TUN 均支持）。ROOT\_TUN 模式下，
  `SystemDnsManager`（由 `EasyTierRootService` 持有）额外通过 root shell 执行
  `settings put global dns1/dns2 100.100.100.101` 切换系统 DNS，并在停止时恢复原值。

* `TomlConfigBuilder.rootTunSpec(profile, globalSettings)` 生成 `RootTunSpec`（含 `ipv4Cidr` /
  `mtu` / `manualRoutes` / `proxyCidrs` / `devName` / `magicDns`），用于 AIDL 传递给 root 进程；
  `devName` 空白时回退到 `easytier0`。

* 轮询 `collectNetworkInfos` 返回的 JSON 中：

  * `my_node_info.virtual_ipv4` 为 `{address:{addr:<u32 big-endian>}, network_length:<u32>}`
    ，需按大端拆解为点分十进制（见 `ipv4InetToCidr`）。

  * `routes[].proxy_cidrs` 是对端可达的远端网络 CIDR（去重排序后作为 TUN 内核路由）。

  * `peer_route_pairs[]` 每项含 `route`（hostname / `ipv4_addr` / `cost` / `path_latency` /
    `path_latency_latency_first` / `stun_info.udp_nat_type`）与可选 `peer`（`conns[]` 含
    `stats.latency_us` / `loss_rate` / `tunnel.tunnel_type`）；`peerRoutePair` 按 cost==1 直连取
    `latency_us`、cost>1 中继取 `path_latency_latency_first`/`path_latency`，映射为 `RuntimePeer`（参照
    easytier-cli 的 `PeerTableItem::from(PeerRoutePair)`）。

  * `stun_info.udp_nat_type` 可为字符串或数字（0–9），由 `natTypeName` 统一为可读名称。

  * `tunnel.tunnel_type` 可能是裸 scheme 或完整 URL，由 `normalizeTunnelType` 取 scheme 部分。

## 关键约定与约束

* **JNI 门面**：应用层只调用 `cc.ptoe.easytier.compose.core.EasyTierJni`，不直接接触
  `com.easytier.jni.EasyTierJNI`。

* **不自动连接**：`MainActivity.onResume()` 有意留空，连接必须由用户显式触发。

* **Web-managed（配置服务器）模式**：`ProfileKind.WEB_MANAGED` 的 profile 由核心的 config-server
  客户端（`startConfigServerClient`）连接并下发配置，应用不实现第二套同步/重连逻辑。应用只做：等待首个
  `run_network_instance` 事件（12s）、用 `api.config.ConfigRpcService/GetConfig` 读取 TOML 并缓存
  （`ProfileRepository.saveWebCache`，失败绝不清空/覆盖缓存）、把 TUN 挂到核心托管实例（`setTunFd` +
  `collectNetworkInfos(instance_name)`）、服务器不可达时回放缓存为普通本地实例、稍后收到配置时升级为 live。
  **Root 模式被拒绝**（客户端在 app 进程，无法管理 root daemon 内的实例）；单服务器多网络只隧道第一个实例，
  其余给出提示。**应用仍然绝不自动连接**，也不在后台（未连接时）同步。

* **配置来源 URL 只在导入、手动刷新与「连接前」读取**：`ProfileLinkSource` 由「Import from link」/
  「Refresh from link」以及 `EasyTierRuntimeCoordinator.start` 里的 `autoRefreshBeforeStart` 调用。
  自动刷新仅发生在用户/开机显式启动某个 profile 时（无 WorkManager、无定时器、无后台轮询、不在应用启动时抓取），
  且仅当 profile 有 `sourceUrl`、`autoRefresh` 开启、距上次成功同步 ≥30 分钟。分享链接在本地解码，不触网；
  `udp/tcp/ws/wss/ring` config-server URL 在「Import from link」中仍视为不可导入的配置（要使用配置服务器，
  请用「Connect to config server」创建 web-managed profile）。

* **运行中不可编辑/删除/切换模式**：`EasyTierViewModel.saveDraft` / `delete` / `updateTunMode` 均检查
  `RuntimeState.STARTING/RUNNING` 并拒绝；`updateTunMode` 另外拒绝把 web-managed profile 切到 ROOT_TUN；
  `resetProfiles` 会先 `coordinator.stop()` 再清空。

* **Hostname 自动补全**：`EasyTierRuntimeCoordinator.start` 通过 `withDeviceHostnameIfBlank` 在
  profile 留空时取 `Settings.Global.DEVICE_NAME`（回退 `Build.MODEL`），剥离 ISO 控制字符并截断到 32
  字符，与 EasyTier Core 的 `get_hostname` 行为一致。

* **GlobalSettings**：TUN 设备名 / `no_tun` 等全局选项独立持久化，所有 profile 共享；
  `ProfileValidator.validate` 与 `TomlConfigBuilder.build` 均需显式传入 `GlobalSettings`。

* **协程**：协调器用 `SupervisorJob + Dispatchers.IO` + `Mutex`；ViewModel 用 `viewModelScope`。

* **DataStore**：profile 损坏时自动迁移到 `profiles_v1_corrupt` 并清空主键，避免崩溃；
  `GlobalSettingsRepository` 使用独立 store（`easytier_global_settings`）。

* **release 构建**：`optimization.enable = false`，R8 实际未启用优化；keep 规则文件
  `app/src/main/keepRules/rules.keep` 当前为空模板。

* **自适应布局阈值**：`screenWidthDp >= 840` 切换为双栏 NavigationRail。

* **edge-to-edge**：`enableEdgeToEdge()` + `WindowInsets.safeDrawing`。

* **国际化**：目前 UI 文案为硬编码英文，仅 `app_name` 走 `strings.xml`。

## 测试

* 单元测试 [ProfileConfigTest.kt](app/src/test/java/cc/ptoe/easytier/compose/ProfileConfigTest.kt)
  覆盖：结构化 TOML 生成（含 `[[port_forward]]` / `[vpn_portal_config]` / `[secure_mode]` / 全 flags
  字段）、DHCP、Root TUN 支持 Magic DNS、`rootTunSpec`（含 dev name 覆盖与空白回退、no\_tun 下禁用 Magic
  DNS 且携带 `noTun` 标志）、no\_tun + ROOT\_TUN 仍输出 `socket_mark`、GlobalSettings 覆盖 TUN 设备名 /
  `no_tun`、字段校验（IPv4/IPv6 CIDR、MTU、peers、proxy networks、port forwards、VPN
  portal、multiThreadCount、bps 限制）。测试通过注入 `NativeConfigParser` 桩绕过真实 JNI。

* [ProfileAutoRefreshTest.kt](app/src/test/java/cc/ptoe/easytier/compose/ProfileAutoRefreshTest.kt)
  覆盖：30 分钟策略（从未同步 / 29:59 / 30:00 / 自定义间隔与注入时钟 / `autoRefresh=false` / 无
  `sourceUrl`）、失败（离线异常、config-server URL、校验失败）不改变已存 profile、成功时应用新配置并保留
  设备级选项与 `sourceSyncedAt`、旧版 JSON（无新字段）仍能反序列化且默认 `autoRefresh=true`。

* [ConfigServerUrlTest.kt](app/src/test/java/cc/ptoe/easytier/compose/ConfigServerUrlTest.kt) 覆盖
  config-server URL 校验：核心接受的 `udp/tcp/ws/wss/ring`（含百分号编码 token）、裸 token、空 token
  （含结尾 `/`）、未知 scheme、缺少 host、`configServerHost` 不泄露 token/凭据。

* [WebManagedConfigTest.kt](app/src/test/java/cc/ptoe/easytier/compose/WebManagedConfigTest.kt) 覆盖：
  `run_network_instance`/`delete_network_instance` 事件解析（含失败/未知/畸形事件）、`GetConfig` payload
  与 `toml_config` 解析、缓存可用性、失败同步不改缓存、live/cached/unavailable 决策、缓存回放
  （instance 名改为 profile id、保留网络配置、hostname/MTU 覆盖、无缓存与坏缓存的本地化错误）、
  缓存摘要、旧 JSON 反序列化为 STATIC 以及 web-managed 字段的序列化往返。

* 修改 `ProfileValidator.kt` / `TomlConfigBuilder.kt` / `NativeConfigParser.kt` / `NetworkInfo.kt` /
  `ProfileAutoRefresh.kt` / `WebManagedConfig.kt` / `EasyTierRuntimeCoordinator.kt` 或 `EasyTierModels.kt`
  后应运行 `./gradlew :app:testDebugUnitTest`。

* instrumented 测试（`ExampleInstrumentedTest`）为占位。

## 权限

`AndroidManifest.xml` 声明：`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`，以及服务级 `BIND_VPN_SERVICE`。VPN 前台服务 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 标注为 `EasyTier virtual network transport`。

## 易踩坑点

1. **修改 AIDL / Parcelable 后**需重新构建以刷新生成的 Stub。
2. **`libeasytier_android_jni.so`** **缺失或架构不匹配**会触发 `UnsatisfiedLinkError`；确认
   `buildRustJniLibs` 已为对应 ABI 构建（`app/build/generated/rustJniLibs/<abi>/`），或 `-PrustJniLibsDir` 指向包含该 ABI 的目录；`-PrustAbis` 只构建了部分 ABI 时，其他 ABI 的 APK 将缺少该库。
3. **Root 模式**依赖设备已 root 且 libsu 能获取 root；`RootService.unbind` 必须在 `bound` 为 true 时才调用。
4. **netlink 路由**：TUN 远端网络路由必须用 `RT_SCOPE_UNIVERSE`（非 `LINK`），否则对任意 CIDR 返回 EINVAL。
5. **IPv4 addr 解析**：EasyTier 序列化的 `addr` 是 `u32::from_be_bytes` 的 big-endian 整数，需拆字节还原。
6. **配置缓存**：修改 build script 后首次构建会重建缓存；某些不兼容的插件可能需关闭。

