# Voice Shield — сборка и установка

## Готовый APK (собран в этом окружении, Gradle 8.2 / AGP 8.2.2 / JDK 17)

    dist/VoiceShield-v1.0-debug.apk
    SHA-256: 9d1e96a9a2f08646effb647568c9351fd19d299d7f14462a543a8332db2b9374

Установка на устройство из того же каталога:

    adb install -r dist/VoiceShield-v1.0-debug.apk

APK подписан отладочным ключом (debug keystore) — этого достаточно для
личной установки. Для Google Play нужен собственный release-ключ (см. ниже).

## Сборка из исходников — Android Studio

1. **Требования:** Android Studio Hedgehog или новее, JDK 17,
   Android SDK Platform 34, Build-Tools 34.0.0.
2. **Открыть проект:** `File → Open…` → выбрать папку `voice_shield/`
   (не `app/`!). Студия сама подхватит `settings.gradle` и wrapper.
3. При первом открыте убедитесь, что `local.properties` содержит корректный
   путь `sdk.dir=` к вашему SDK (Studio создаёт его автоматически).
4. **Сборка debug-APK:** `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
   Результат: `app/build/outputs/apk/debug/app-debug.apk`.
5. Быстрая проверка без Studio (CLI):

       ./gradlew assembleDebug          # Linux/macOS
       gradlew.bat assembleDebug        # Windows

   APK появится в `app/build/outputs/apk/debug/`.

## Структура проекта (все файлы нужны для сборки)

    voice_shield/
    ├── settings.gradle                  корневая конфигурация Gradle
    ├── build.gradle                     версии плагинов (AGP 8.2.2, Kotlin 1.9.22)
    ├── gradle.properties                JVM-настройки, AndroidX
    ├── gradlew / gradlew.bat            скрипты Gradle Wrapper
    ├── gradle/wrapper/                  gradle-wrapper.jar + .properties
    └── app/
        ├── build.gradle                 namespace, minSdk 29, зависимости
        └── src/main/
            ├── AndroidManifest.xml      разрешения, Activity, foreground-сервис
            ├── java/dev/shield/voiceshield/
            │   ├── MainActivity.kt      UI и запрос разрешений
            │   ├── ShieldViewModel.kt   оркестрация щита, лог, уведомление
            │   ├── NoiseEngine.kt       генераторы WHITE/PINK/SPEECHBAND
            │   ├── MicSentinel.kt       захват/аудит микрофона
            │   └── ShieldService.kt     foreground-сервис mediaPlayback
            └── res/
                ├── layout/activity_main.xml
                └── values/strings.xml

## Подпись release-сборки собственным ключом (опционально)

1. Сгенерируйте хранилище ключей:

       keytool -genkeypair -v -keystore my-release.keystore \
         -alias voiceshield -keyalg RSA -keysize 2048 -validity 10000

2. В `app/build.gradle` замените во block `release { … }` строку
   `signingConfig signingConfigs.debug` на собственную `signingConfig`,
   описанную через `signingConfigs` (пути/пароли храните в
   `~/.gradle/gradle.properties`, не в репозитории).
3. Соберите: `./gradlew assembleRelease`.

## Известные ограничения сборки

- minSdk 29 (Android 10+): ниже нет гарантированного индикатора
  использования микрофона и типов foreground-сервисов.
- Ошибка «ForegroundServiceType missing» при установке на Android 14
  отсутствует: манифест объявляет `foregroundServiceType="mediaPlayback"`
  и разрешение `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
