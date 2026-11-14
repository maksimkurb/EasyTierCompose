[English](README.md) | Русский

# EasyTier Compose

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.02-blue.svg)](https://developer.android.com/jetpack/compose)

Android-клиент для [EasyTier](https://github.com/EasyTier/EasyTier) — децентрализованной mesh-VPN, написанной на Rust.
Это приложение из одной Activity на Jetpack Compose: оно подключает ядро EasyTier через JNI и добавляет нативное управление
профилями, оркестрацию сети и два способа подключения виртуальной сети к системе (режимы TUN).

## Возможности

- **Чистый Compose UI** — Material 3 и адаптивная вёрстка (NavigationBar на телефонах, NavigationRail на планшетах)
- **Два режима TUN** — `VPN_SERVICE` (без root, через системный запрос VPN) и `ROOT_TUN` (root-процесс и настоящий интерфейс `easytier0`)
- **JNI-фасад** — код приложения не обращается к нативным символам напрямую, всё идёт через `core.EasyTierJni`
- **Хранение в DataStore** — несколько профилей и выбранный профиль, с автоматическим восстановлением при повреждении данных
- **Полный редактор конфигурации** — форма по разделам, покрывающая все поля ядра EasyTier (General / Network & Peers / Routing / IPv6 /
  Port Forwards / VPN Portal / Secure Mode / STUN & Whitelists / Flags — General / P2P / KCP / QUIC); сгенерированный структурированный
  TOML проверяется нативным `parseConfig`
- **Magic DNS** — встроенный DNS EasyTier (`100.100.100.101`) в обоих режимах TUN; `ROOT_TUN` дополнительно переключает системный DNS через root-оболочку
- **Экран узлов** — узлы времени выполнения: виртуальный IP, задержка, протокол туннеля, потери пакетов, тип NAT и стоимость (напрямую / через ретранслятор)
- **Глобальные переопределения** — имя устройства TUN, `no_tun` и SOCKS5 (порт и адрес прослушивания) общие для всех профилей
- **Автозаполнение имени хоста** — если в профиле оно пустое, берётся имя Android-устройства (как в `get_hostname` ядра)
- **Оркестрация на корутинах** — `SupervisorJob + Mutex` сериализует автомат состояний и исключает гонки
- **Локализация** — английский и русский (плюс неполный китайский); выбор языка для приложения в Android 13+

## Скриншоты

<table>
  <tr>
    <td width="50%" align="center"><b>Главная (Dashboard)</b></td>
    <td width="50%" align="center"><b>Профили</b></td>
  </tr>
  <tr>
    <td><img src="docs/dashboard.png" alt="Главная"></td>
    <td><img src="docs/profiles.png" alt="Профили"></td>
  </tr>
  <tr>
    <td align="center"><b>Узлы</b></td>
    <td align="center"><b>Настройки</b></td>
  </tr>
  <tr>
    <td><img src="docs/peers.png" alt="Узлы"></td>
    <td><img src="docs/settings.png" alt="Настройки"></td>
  </tr>
</table>

Скриншоты сделаны в английском интерфейсе.

## Режимы TUN

| | `VPN_SERVICE` | `ROOT_TUN` |
|---|---|---|
| Реализация | `VpnService.Builder` | `/dev/net/tun` + netlink |
| Разрешение | системный запрос на VPN | root (IPC через libsu `RootService`) |
| Служба переднего плана | `specialUse` | нет (работает внутри root-процесса) |
| Magic DNS | поддерживается (`100.100.100.101`) | поддерживается (плюс `settings put global dns1` для смены системного DNS) |
| Имя устройства | Android VPN (виртуальное) | `easytier0` (настоящий интерфейс) |

Третий вариант, `NO_TUN`, запускает ядро без устройства TUN (для сценариев только с SOCKS5 или пробросом портов).
Для `ROOT_TUN` нужно устройство с root и выданные приложению права root. Подробности — в [AGENTS.md](AGENTS.md).

## Установка

### Репозиторий F-Droid

Добавьте этот репозиторий в F-Droid (или Droidify, Neo Store и т. п.):

```
https://maksimkurb.github.io/EasyTierCompose/fdroid/repo
```

Перед тем как доверять репозиторию, сверьте отпечаток его ключа подписи. Отпечаток формирует CI при каждой публикации;
он выводится в итогах задания и в опубликованном файле
[`repo-info.md`](https://maksimkurb.github.io/EasyTierCompose/fdroid/repo-info.md):

```
SHA-256: <подставляется CI, см. repo-info.md>
```

Прямая ссылка (подставьте вместо метки отпечаток):
`https://maksimkurb.github.io/EasyTierCompose/fdroid/repo?fingerprint=<ОТПЕЧАТОК>`

### APK

Подписанные APK для каждой архитектуры и их суммы SHA-256 прикладываются к каждому
[релизу на GitHub](https://github.com/maksimkurb/EasyTierCompose/releases).
Ключ подписи APK отличается от ключа репозитория F-Droid, поэтому приложение, установленное из одного
источника, нельзя обновить из другого без переустановки.

## Сборка из исходников

### Требования

| Инструмент | Примечание |
|---|---|
| JDK 21 | JVM демона Gradle зафиксирована на 21 (`gradle/gradle-daemon-jvm.properties`; при отсутствии загружается через foojay resolver) |
| Android SDK | platform 37, build-tools 37.0.0; примите лицензии (`sdkmanager --licenses`) |
| Android NDK `30.0.16248370` и CMake `4.1.2` | AGP устанавливает их по требованию, когда лицензии приняты |
| Rust 1.95 (через [rustup](https://rustup.rs)) | версия закреплена в `EasyTier-Core/rust-toolchain.toml` |
| `cargo-ndk` | `cargo install cargo-ndk --locked` |
| `protoc` | компилятор Protocol Buffers из пакетного менеджера |
| git | ядро EasyTier подключено как submodule |

Rust-компоненты ставятся один раз (сборка сама ничего не устанавливает: она только проверяет окружение и сообщает, чего не хватает):

```bash
rustup toolchain install 1.95 --profile minimal \
  -t aarch64-linux-android -t armv7-linux-androideabi -t x86_64-linux-android
cargo install cargo-ndk --locked
```

### Сборка

```bash
git clone --recurse-submodules https://github.com/maksimkurb/EasyTierCompose.git
cd EasyTierCompose
# клонировали без submodule?
git submodule update --init --recursive

./gradlew :app:assembleDebug      # debug-APK (по одному на архитектуру)
./gradlew :app:assembleRelease    # release-APK (подписаны, если заданы ключи, см. ниже)
./gradlew :app:testDebugUnitTest  # модульные тесты
```

Первая сборка компилирует ядро EasyTier через `cargo-ndk` для `arm64-v8a`, `armeabi-v7a` и `x86_64`
(это долго, время см. в таблице ниже). Результат попадает в `app/build/generated/rustJniLibs/<abi>/` и автоматически
подключается к APK. Сборка инкрементальная: ничего не пересобирается, пока не изменятся коммит submodule, исходники ядра,
список ABI, NDK или список патчей.

| Свойство Gradle | Назначение |
|---|---|
| `-PrustAbis=arm64-v8a,x86_64` | собрать только часть ABI (быстрее при локальной разработке; по умолчанию все три) |
| `-PskipRustBuild -PrustJniLibsDir=<dir>` | пропустить Cargo и упаковать уже собранные `<dir>/<abi>/libeasytier_android_jni.so` (используется в CI) |
| `-PrustPatches=<файл>[,<файл>]` | применить при сборке необязательные патчи из [`patches/`](patches/README.md) к ядру |

`./gradlew :app:buildRustJniLibs` собирает только нативные библиотеки.

Подпись релиза: скопируйте `keystore.properties.example` в `keystore.properties` (исключён из git) или задайте
`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Без ключей release-вариант подписывается отладочным ключом.

Публикация (CI, ключи подписи, репозиторий F-Droid): [docs/RELEASING.ru.md](docs/RELEASING.ru.md).

## Конфиденциальность

- В приложении **нет телеметрии, аналитики, отчётов о сбоях, рекламы и сервисов Google**.
- Единственный сетевой запрос, который делает код самого приложения, — **«Импорт по ссылке»** на экране профилей:
  HTTP(S)-запрос `GET` по введённому вами URL после нажатия «Импортировать».
- Ядро EasyTier — это VPN: оно обменивается данными с узлами, которые вы настроили. Кроме того, чтобы определить тип NAT и свой
  публичный адрес (нужны для обхода NAT, hole punching), по умолчанию оно обращается к **публичным STUN-серверам**; они видят ваш
  публичный IP-адрес и используемый UDP/TCP-порт:

  | Вид | Серверы по умолчанию (EasyTier-Core `81487c3`, `easytier-core/src/config/mod.rs`) |
  |---|---|
  | UDP | `stun.easytier.cn` (DNS-запрос `TXT`, возвращающий список серверов), `stun.miwifi.com`, `stun.chat.bilibili.com`, `stun.hitv.com` |
  | UDP, IPv6 | `stun-v6.easytier.cn` (DNS-запрос `TXT`) |
  | TCP | `stun.hot-chilli.net`, `stun.fitauto.ru`, `fwa.lifesizecloud.com`, `global.turn.twilio.com`, `turn.cloudflare.com`, `stun.voip.blackberry.com`, `stun.radiojar.com` |

  Свои серверы можно задать в профиле (*Редактор → STUN и белые списки*): непустой список заменяет серверы по умолчанию.
  Необязательный патч, убирающий серверы по умолчанию полностью, описан в [patches/README.md](patches/README.md)
  (он ухудшает обход NAT).
- Если узлы заданы URI вида `http(s)://`, `txt://` или `srv://`, ядро обращается к этим хостам, чтобы получить адреса.
- Ядро может использовать UPnP/NAT-PMP на локальном шлюзе (отключается флагом «Отключить UPnP»).
- Профили, включая секреты сетей, хранятся только в закрытом хранилище приложения на устройстве.

## Структура проекта

```
app/src/main/java/cc/ptoe/easytier/compose/
├── MainActivity.kt              # единственная Activity, ручное внедрение зависимостей
├── core/                        # JNI-фасад + координатор выполнения + генерация конфигурации
├── data/                        # модели + хранение в DataStore
├── transport/                   # абстракция TUN и две реализации (vpn/, root/)
└── ui/                          # Compose UI, ViewModel, тема
buildSrc/                        # задача Gradle, собирающая ядро на Rust (cargo-ndk)
EasyTier-Core/                   # git submodule: https://github.com/EasyTier/EasyTier
patches/                         # необязательные патчи ядра, применяемые при сборке
```

Соглашения разработки и архитектура для контрибьюторов и ИИ-агентов: [AGENTS.md](AGENTS.md).

## Лицензия

Проект распространяется под [GNU General Public License v3](LICENSE).

```
EasyTier Compose
Copyright (C) 2026 EasyTier Compose Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Встроенное ядро EasyTier (submodule `EasyTier-Core/`, <https://github.com/EasyTier/EasyTier>) распространяется под
[LGPL-3.0](EasyTier-Core/LICENSE); при распространении или изменении кода внутри этого submodule нужно соблюдать условия LGPL-3.0.

## Благодарности

- [EasyTier](https://github.com/EasyTier/EasyTier) — децентрализованное VPN-ядро, которое использует приложение
- [libsu](https://github.com/topjohnwu/libsu), topjohnwu — root-оболочка и IPC через `RootService` (Apache-2.0)
