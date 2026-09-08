# BLE Studio stack research (2026-09-08)

## Version baseline and reproducibility

Versions below are the latest stable versions observed on the cited release pages on the research date. AndroidX and Compose publish independently; use the Compose BOM for BOM-managed Compose artifacts and pin non-BOM artifacts explicitly. Re-check release pages before a future upgrade.

* **AGP 9.4.0**, **Gradle 9.6.0**, and **JDK 17**: AGP's compatibility table specifies Gradle 9.6 (minimum/default), JDK 17 (minimum/default), and API 37 support ([AGP 9.4 notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes)).
* **Kotlin 2.3.20** is the current stable 2.3 line (the Kotlin release documentation records the 2.3.20 release); use the Compose compiler plugin at the same Kotlin version. Kotlin 2.3.0's release notes explain the 2.3 line ([release process](https://kotlinlang.org/docs/releases.html), [2.3.0 notes](https://kotlinlang.org/docs/whatsnew23.html)).
* **Compose BOM 2026.08.00** is stable. BOM-managed `material3` is **1.4.0**. The BOM intentionally does not include the compiler; since Kotlin 2.0, the compiler plugin tracks Kotlin ([BOM guide](https://developer.android.com/develop/ui/compose/bom), [Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)).
* `androidx.compose.material3:material3-adaptive:1.3.0` is stable and `androidx.compose.material3:material3-adaptive-navigation-suite` currently has **1.5.0-alpha27** as its latest line (the stable Material3 page lists that alpha; do not call it stable). For an all-stable build, use the last stable navigation-suite release available in the release page/BOM mapping; accepting the alpha is reasonable for the adaptive tablet shell ([Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3), [adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)).
* Navigation Compose **2.10.0** is stable; Navigation 3 **1.2.0-beta01** is not stable, so choose Navigation Compose for the first production cut ([Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation), [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3)).
* Lifecycle **2.11.0** is stable (`lifecycle-viewmodel-compose`) ([Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle)).
* Room **2.8.4** (runtime, KTX, compiler), DataStore **1.2.1**, Activity Compose **1.11.0**, and Core KTX **1.17.0** are the current stable versions cited by their AndroidX release pages ([Room](https://developer.android.com/jetpack/androidx/releases/room), [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore), [Activity](https://developer.android.com/jetpack/androidx/releases/activity), [Core](https://developer.android.com/jetpack/androidx/releases/core)).
* `kotlinx-coroutines` **1.11.0** is stable (Maven metadata); `kotlinx-serialization-json` **1.11.0** is the latest stable while 1.12.0-RC is prerelease ([coroutines metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml), [serialization metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml)).

### Ready-to-paste `gradle/libs.versions.toml`

```toml
[versions]
agp = "9.4.0"
gradle = "9.6.0" # wrapper distribution; JDK 17 required
kotlin = "2.3.20"
composeBom = "2026.08.00"
material3 = "1.4.0"
adaptive = "1.3.0"
# Latest navigation-suite line is alpha; replace with a stable line if alpha is disallowed.
navigationSuite = "1.5.0-alpha27"
navigation = "2.10.0"
lifecycle = "2.11.0"
serialization = "1.11.0"
coroutines = "1.11.0"
room = "2.8.4"
datastore = "1.2.1"
activity = "1.11.0"
core = "1.17.0"
# BLE choice (Kable)
kable = "0.43.1"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
material3 = { module = "androidx.compose.material3:material3", version.ref = "material3" }
material3-adaptive-navigation-suite = { module = "androidx.compose.material3:material3-adaptive-navigation-suite", version.ref = "navigationSuite" }
material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive", version.ref = "adaptive" }
material3-adaptive-layout = { module = "androidx.compose.material3.adaptive:adaptive-layout", version.ref = "adaptive" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
core-ktx = { module = "androidx.core:core-ktx", version.ref = "core" }
kable-core = { module = "com.juul.kable:kable-core", version.ref = "kable" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.3.20-2.0.2" }
```

Apply `org.jetbrains.kotlin.plugin.compose`; do not set the old `kotlinCompilerExtensionVersion` with Kotlin 2.x ([Compose compiler plugin guide](https://developer.android.com/develop/ui/compose/compiler)). KSP's version must be selected from the KSP release matching the exact Kotlin compiler; the value above is an example coordinate to verify at implementation time.

## BLE API/library choice

### Kable

Maven: `com.juul.kable:kable-core:0.43.1` ([Maven Central](https://central.sonatype.com/artifact/com.juul.kable/kable-core), [source](https://github.com/JuulLabs/kable)). Kable exposes coroutine/Flow scanning (`Scanner.advertisements`), `Peripheral` connection and service discovery, characteristic read/write, notifications/indications, and Android scan settings. It is a clean central/client API and supports Android/iOS/JVM/JS. It does **not** provide an Android peripheral/GATT-server role; use raw Android server APIs for that feature. PHY and MTU support are not as complete/documented as Nordic's Android-specific API, so an explorer must add an `expect/actual` or Android escape hatch where needed.

### Nordic Kotlin BLE Library

The new library's coordinates are `no.nordicsemi.kotlin.ble:client-android:1.3.1` (plus its `client-api`, `core`, and profile modules as needed; [Maven Central search](https://central.sonatype.com/search?q=no.nordicsemi.kotlin.ble), [repository](https://github.com/NordicSemiconductor/Kotlin-BLE-Library)). Its documented central matrix has scanning, connect/autoConnect, discovery, read/write, notifications/indications, highest-MTU request, PHY request/observation, connection priority and connection parameter observation. Version 2 is explicitly a rewrite/Beta; its status table still marks peripheral GATT-server setup/operations unchecked, although advertising is checked.

For a server, the older mature Nordic Android BLE library is `no.nordicsemi.android:ble:2.9.0` (and `no.nordicsemi.android:ble-ktx:2.9.0` where available); its README documents GATT-server support since 2.2 ([repository](https://github.com/NordicSemiconductor/Android-BLE-Library)). Do not confuse this artifact family with the new `no.nordicsemi.kotlin.ble` rewrite.

### Raw `android.bluetooth`

The platform API (`BluetoothLeScanner`, `BluetoothGatt`, `BluetoothGattServer`, `BluetoothGattService`, `BluetoothGattCharacteristic`) has no Maven dependency and is the authority for scanning, connect/discover, read/write, notifications, MTU (`requestMtu`), PHY (`setPreferredPhy`/`readPhy`), advertising, and peripheral server mode. It requires callback/state-machine code, serialization of GATT operations, API-level guards, and careful permission handling.

**Recommendation:** use Nordic's mature `no.nordicsemi.android:ble` client plus its GATT-server APIs if peripheral mode is a real acceptance criterion; it gives Android-specific MTU/PHY/server coverage and battle-tested operation sequencing. If peripheral mode is explicitly phase-two, Kable is the nicest coroutine API for a central-only first release, but retain a small platform adapter so raw PHY/MTU/server operations are not blocked. Avoid the new Nordic Kotlin rewrite as the sole dependency until its server checklist is complete.

## BLE permissions and foreground service

For target SDK 31+, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` are runtime Nearby devices permissions. `neverForLocation` is permitted only when the app can strongly assert that scan results never derive physical location. For API 30 and lower, use legacy Bluetooth permissions and runtime `ACCESS_FINE_LOCATION`; Android 10/11 background scans may additionally require `ACCESS_BACKGROUND_LOCATION` ([official permission matrix](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)).

```xml
<manifest ...>
    <!-- API <= 30 -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

    <!-- API 31+: BLE central + optional peripheral/server -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <!-- API 34+ long-running connected-device FGS -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <application ...>
        <service android:name=".ble.BleForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

Only include `BLUETOOTH_ADVERTISE` if advertising/server mode ships. Remove `neverForLocation` and keep/request location if the product ever infers location. On API 34+, declare the `connectedDevice` type and `FOREGROUND_SERVICE_CONNECTED_DEVICE`; before `startForeground`, obtain at least one qualifying runtime permission (Bluetooth permissions qualify). A foreground service type is mandatory for every FGS on API 34+ ([FGS type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)). Request runtime permissions in the Activity and start the service only after grant.

## Tablet Material 3 patterns and custom palettes

Use `NavigationSuiteScaffold` to adapt bottom navigation on compact widths to rail (and, where appropriate, drawer) on tablets; provide its selected destination from the navigation state. Compute adaptive information using `currentWindowAdaptiveInfo()` and inspect `windowSizeClass` ([adaptive navigation guide](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation), [window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)). For the explorer's device/session list and selected GATT/session detail, use `ListDetailPaneScaffold` from `androidx.compose.material3.adaptive:adaptive-layout:1.3.0`; it presents side-by-side panes when expanded and one pane when compact/medium ([adaptive release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)). Keep navigation destinations semantic rather than encoding a fixed phone layout.

When the user chooses a custom palette, make that an explicit preference (`System`, `Light`, `Dark`, `Custom #n`) and **do not** overlay dynamic color on top of a custom scheme. Generate/store 20 immutable schemes as code (20 light + corresponding 20 dark `ColorScheme`s, or 20 `Palette` definitions from which light/dark schemes are constructed), and persist only the integer selection and mode in Preferences DataStore. Example model:

```kotlin
@Serializable
data class ThemePrefs(val paletteId: Int = 0, val followSystem: Boolean = true)

val Context.themePrefs: DataStore<Preferences> by preferencesDataStore("theme")
val PALETTE_ID = intPreferencesKey("palette_id")

// In repository: dataStore.data.map { it[PALETTE_ID] ?: 0 }
// UI: val prefs by vm.themePrefs.collectAsStateWithLifecycle()
val scheme = when {
    prefs.followSystem && dynamicColorAllowed -> dynamicLightColorScheme(context)
    prefs.followSystem -> if (dark) darkColorScheme() else lightColorScheme()
    dark -> customDarkSchemes[prefs.paletteId]
    else -> customLightSchemes[prefs.paletteId]
}
MaterialTheme(colorScheme = scheme) { content() }
```

Do not persist all color values in DataStore: palette definitions are app assets/code; DataStore stores the stable selection, making migrations and export evidence deterministic. Expose a palette preview and validate IDs 0..19.

## Signed GitHub release pipeline

`release.yml` below builds a universal/single APK (avoid split APKs for Obtainium), decodes a base64 keystore secret into the runner's temporary directory, and uploads an asset to the GitHub release. `softprops/action-gh-release@v2` publishes the APK ([action](https://github.com/softprops/action-gh-release), [Gradle setup](https://github.com/gradle/actions), [Java setup](https://github.com/actions/setup-java)).

### `app/build.gradle.kts` signing snippet

```kotlin
android {
    signingConfigs {
        create("release") {
            val storeFilePath = providers.environmentVariable("RELEASE_STORE_FILE")
            storeFile = storeFilePath.map(::file).orNull
            storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### `.github/workflows/release.yml`

```yaml
name: Release APK
on:
  push:
    tags: ["v*"]
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode release keystore
        env:
          KEYSTORE_B64: ${{ secrets.RELEASE_KEYSTORE_B64 }}
        run: |
          test -n "$KEYSTORE_B64"
          printf '%s' "$KEYSTORE_B64" | base64 --decode > "$RUNNER_TEMP/ble-studio-release.jks"
          chmod 600 "$RUNNER_TEMP/ble-studio-release.jks"
      - name: Build signed release APK
        env:
          RELEASE_STORE_FILE: ${{ runner.temp }}/ble-studio-release.jks
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease --no-daemon
      - name: Rename universal APK
        run: cp app/build/outputs/apk/release/app-release.apk "BLE-Studio-${GITHUB_REF_NAME}.apk"
      - name: Publish GitHub release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: BLE-Studio-${{ github.ref_name }}.apk
```

Generate the keystore non-interactively (store the resulting base64 only in the secret manager):

```bash
keytool -genkeypair -v -keystore ble-studio-release.jks -storetype JKS \
  -alias ble-studio -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$RELEASE_STORE_PASSWORD" -keypass "$RELEASE_KEY_PASSWORD" \
  -dname "CN=BLE Studio, OU=Mobile, O=BLE Studio, L=Unknown, ST=Unknown, C=US"
base64 -w0 ble-studio-release.jks > ble-studio-release.jks.b64
```

Obtainium's GitHub source reads release metadata/assets; the asset must be a directly downloadable `.apk`. Use one universal APK named predictably (for example `BLE-Studio-v1.2.3.apk`) to avoid architecture filtering and split-selection prompts. If publishing multiple APKs, users must configure Obtainium's APK regex; the wiki documents regex filtering and CPU-architecture filename heuristics ([Obtainium source rules](https://wiki.obtainium.imranr.dev/sources/)). Set `versionName` to match the tag's semantic version (`v1.2.3` tag and `1.2.3` versionName); the tag itself is selected by the GitHub source. The exact URL users add is the repository URL: `https://github.com/<OWNER>/<REPO>` (not the asset URL); Obtainium then follows releases and selects the APK asset. For a direct, non-updating install, use the asset URL, but that forfeits GitHub release tracking.

## Recommended architecture and open-source references

Use MVVM + repository boundaries:

1. **BLE data source**: scanner/connection adapter serializes GATT operations, exposes typed `Flow`s for scan results, connection state, services, notifications, MTU and PHY.
2. **Repository**: owns the adapter and maps callbacks to domain records; persists a session, command labels, raw TX/RX bytes, timestamps, UUIDs, RSSI, MTU/PHY and errors.
3. **Room**: `Session`, `GattService`, `GattCharacteristic`, `BleEvent`, and `ReplayCommand` entities; use transactions when a replay command and resulting event are recorded together.
4. **ViewModels**: expose immutable `StateFlow<UiState>` and event flows; use `collectAsStateWithLifecycle` in Compose ([Lifecycle guidance](https://developer.android.com/topic/libraries/architecture/lifecycle)).
5. **Compose/navigation**: Navigation Compose 2.10 routes top-level destinations; adaptive pane state handles device/session detail without duplicating business state. Export builds a versioned evidence bundle (JSON metadata + newline-delimited event JSON + binary payload files/checksums).

Useful references: [Kable](https://github.com/JuulLabs/kable) for coroutine BLE API ideas; Nordic's [Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) for operation queues, MTU/PHY and server support; Nordic's [Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library) for Flow-oriented API shape and mocks. Searchable community examples include GitHub projects titled **BLE Scanner Compose**; inspect maintenance/activity before copying. nRF Connect is proprietary/closed-source, so use it only as a product-behavior reference, not source. `blessed-android` is a Java Android BLE client (not Compose and not a GATT-server solution), useful for callback/state-machine comparison. The Nordic “Android-nRF-Toolbox Compose rewrite” is a sample/reference direction rather than a stable reusable BLE explorer dependency.

## Sources

All version and behavior claims above link to Android Developers/AndroidX release documentation, official Kotlin documentation, Maven Central metadata, or the relevant library's upstream GitHub repository. Version-specific prerelease status is intentionally called out rather than presented as stable.
