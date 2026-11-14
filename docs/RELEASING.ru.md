[English](RELEASING.md) | Русский

# Выпуск релизов и публикация

Обзор конвейера (все workflow лежат в `.github/workflows/`):

| Workflow | Запуск | Что делает |
|---|---|---|
| `build-native.yml` | вызывается из других или вручную | Собирает `libeasytier_android_jni.so` для `arm64-v8a`, `armeabi-v7a`, `x86_64` задачей Gradle `:app:buildRustJniLibs`. Кэшируется по коммиту ядра, версии Rust, версии NDK и хэшу скриптов сборки; при точном попадании в кэш ничего не компилируется. Загружает артефакт `native-jni-libs`. |
| `build-app.yml` | pull request, push в `main` | Нативные библиотеки из кэша/артефакта, модульные тесты, lint, **debug**-APK (артефакт). |
| `release.yml` | тег `v*` | Проверяет, что тег совпадает с `version.txt`, собирает **подписанные release**-APK (по одному на ABI), прикладывает их и `SHA256SUMS.txt` к релизу на GitHub. |
| `fdroid.yml` | после успешного запуска `Release` или вручную (`workflow_dispatch`) | Добавляет APK релиза в репозиторий F-Droid, подписывает индекс ключом репозитория, публикует `fdroid/` в ветку `gh-pages`. Хранит последние N версий (по умолчанию 5). |

## Секреты

Добавляются в *Settings → Secrets and variables → Actions → Repository secrets*. Не коммитьте их и не выводите в логи.

| Секрет | Где используется | Содержимое |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | `release.yml` | base64 хранилища ключей для **подписи APK** (`.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | `release.yml` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | `release.yml` | псевдоним ключа в хранилище |
| `ANDROID_KEY_PASSWORD` | `release.yml` | пароль ключа |
| `FDROID_KEYSTORE_BASE64` | `fdroid.yml` | base64 хранилища ключей **репозитория F-Droid** (PKCS12, псевдоним `repokey`) |
| `FDROID_KEYSTORE_PASS` | `fdroid.yml` | пароль хранилища |
| `FDROID_KEY_PASS` | `fdroid.yml` | пароль ключа (для PKCS12 совпадает с паролем хранилища) |

Ключи намеренно разные: **ключ APK** подписывает приложение (установленные копии обновляются только APK с той же подписью),
**ключ репозитория** подписывает индекс F-Droid (клиенты запоминают его отпечаток). Храните офлайн-копии обоих хранилищ:
потеряв ключ APK, вы не сможете обновлять уже установленные приложения; потеряв ключ репозитория, заставите всех пользователей добавить репозиторий заново.

## Создание хранилищ ключей

Делайте это на своей машине, вне репозитория, а файлы храните в менеджере паролей или в офлайн-копии.

Хранилище для подписи APK:

```bash
keytool -genkeypair -v -keystore easytier-release.jks -alias easytier \
  -keyalg RSA -keysize 4096 -validity 10000
# задайте надёжный пароль; псевдоним сохраните в ANDROID_KEY_ALIAS
```

Хранилище ключа репозитория F-Droid (псевдоним должен быть `repokey`, см. `fdroid/config.yml`):

```bash
keytool -genkeypair -v -keystore easytier-fdroid-repo.p12 -storetype PKCS12 -alias repokey \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=EasyTier Compose F-Droid repository, O=maksimkurb"
```

Секреты сохраняются через GitHub CLI (значение читается из stdin, в истории оболочки ничего не остаётся):

```bash
base64 -w0 easytier-release.jks        | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD     # вставьте значение, Enter, Ctrl-D
gh secret set ANDROID_KEY_ALIAS             # easytier
gh secret set ANDROID_KEY_PASSWORD

base64 -w0 easytier-fdroid-repo.p12    | gh secret set FDROID_KEYSTORE_BASE64
gh secret set FDROID_KEYSTORE_PASS
gh secret set FDROID_KEY_PASS               # для PKCS12 то же значение, что и FDROID_KEYSTORE_PASS
```

Локальные release-сборки используют `keystore.properties` (см. `keystore.properties.example`), он исключён из git.

## Однократная настройка GitHub

1. **Pages**: *Settings → Pages → Build and deployment → Source: Deploy from a branch*, ветка `gh-pages`, папка `/ (root)`.
   Ветку создаёт первый запуск `fdroid.yml`. Адрес репозитория:
   `https://maksimkurb.github.io/EasyTierCompose/fdroid/repo`.
2. **Права workflow**: в *Settings → Actions → General → Workflow permissions* можно оставить «Read repository contents»;
   задания, которым нужна запись (`release.yml`, `fdroid.yml`), сами запрашивают `contents: write`.
3. Добавьте семь секретов из таблицы выше.

## Как выпустить релиз

1. Укажите новую версию в `version.txt` (например, `1.0.11`) и закоммитьте в `main`
   (в PowerShell это автоматизирует `release.ps1`: повышение версии, коммит, тег и push).
2. Создайте тег и отправьте его: `git tag v1.0.11 && git push origin v1.0.11`. Тег должен быть равен `v` + содержимое `version.txt`.
3. `release.yml` соберёт подписанные APK и создаст релиз на GitHub. После успеха автоматически запустится `fdroid.yml`:
   он добавит APK в репозиторий и опубликует его. В итогах задания выводятся адрес репозитория и его **отпечаток**
   (он же записывается в `fdroid/repo-info.md` на Pages: `https://maksimkurb.github.io/EasyTierCompose/fdroid/repo-info.md`).
4. Сверьте этот отпечаток с тем, который покажет F-Droid при добавлении репозитория.

Чтобы пересобрать репозиторий без нового релиза, запустите *F-Droid repository* вручную
(Actions → F-Droid repository → Run workflow). Параметры: `tag` (по умолчанию последний релиз) и `keep` (по умолчанию 5).

`versionCode` — это число коммитов в истории (`git rev-list --count HEAD`), поэтому workflow делают checkout с
`fetch-depth: 0`. Не переписывайте историю после релиза: меньшее число не даст клиентам обновиться.

## Замечания о репозитории F-Droid

* Состояние репозитория хранится в дереве `gh-pages` (`fdroid/repo/*.apk`). Каждая публикация заменяет ветку одним «сиротским» коммитом
  (force-push), поэтому старые APK не накапливаются в истории. Не храните на `gh-pages` ничего другого.
* Размер сайта GitHub Pages ограничен примерно 1 ГБ; при трёх APK на версию пять версий укладываются с большим запасом.
* `fdroid/build-repo.sh` можно запускать локально (нужны `fdroidserver`, JDK для `keytool` и `ANDROID_HOME` с build-tools):
  ```bash
  FDROID_KEYSTORE_FILE=easytier-fdroid-repo.p12 FDROID_KEY_STORE_PASS=... FDROID_KEY_PASS=... \
    fdroid/build-repo.sh --apks путь/к/apk --pages /tmp/pages --keep 5
  ```
* Тексты для магазина (английский и русский) лежат в `fdroid/metadata/cc.ptoe.easytier.compose/`; значок и скриншоты берутся из
  `app/src/main/ic_launcher-playstore.png` и `docs/`.
* Включение в основной репозиторий F-Droid — отдельный шаг (рецепт в `fdroiddata`). Сборка Gradle ему не противоречит: бинарные файлы не коммитятся,
  нативная библиотека собирается из submodule (в рецепте нужны `submodules: true`, `protobuf-compiler`, Rust 1.95 с Android-целями и `cargo-ndk`
  в шагах `prebuild`/`sudo`/`init`). Такой рецепт не написан и не проверялся.
