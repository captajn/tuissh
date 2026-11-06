# Tui SSH

An Android SSH client in Kotlin and Jetpack Compose: a VT terminal, an SFTP browser,
host metrics, container management and an AI assistant that can act on the session.

## What it does

- **Terminal** — VT100/xterm emulation with a pty, 5000 lines of scrollback, and a key
  bar for the keys a phone keyboard lacks (Ctrl, Alt, Esc, arrows, Ctrl-C).
- **SFTP** — browse, upload and download through the Storage Access Framework, plus
  create, rename, chmod and delete.
- **Metrics** — CPU, memory, disks and top processes read from `/proc` and `df`.
- **Containers** — Docker and Podman: list, start, stop, restart, read logs.
- **Assistant** — chat with an OpenAI-compatible or Anthropic-compatible provider;
  the model can call tools that read files, metrics and containers, and can run a
  shell command only after you approve it.
- **Security** — host keys are verified against a fingerprint of the real key blob, not
  a label; credentials are encrypted with the Android Keystore; passwords and
  passphrases are cached in memory only, for 15 minutes.

Vietnamese is the default language, with English available.

## Requirements

- JDK 21 (Gradle 8.9 does not run on newer JDKs)
- Android SDK with platform 35, `ANDROID_HOME` set
- minSdk 26, targetSdk 35

## Build

```bash
cd neo-android
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

On Windows, use JDK 21 explicitly: the JDK bundled with recent Android Studio is too
new for Gradle 8.9.

## Tests

The suite is offline by default: 309 unit and integration tests that need no network.
SSH, SFTP and key auth run against an in-process Apache MINA SSHD server.

Anything requiring real infrastructure is skipped unless you provide it:

```bash
# A real Linux host, for the metrics and container suites
export LIVE_HOST=your-server
export LIVE_USER=ubuntu
export LIVE_KEY_PATH=/path/to/private-key

# A real provider, for the assistant suites
export AI_TEST_KEY=your-api-key
export AI_TEST_BASE=https://api.openai.com/v1
export AI_TEST_MODEL=gpt-4o

./gradlew testDebugUnitTest
```

Without those variables those tests skip, so a fork builds green with no secrets.

## Layout

```
neo-android/
  app/src/main/java/com/tuissh/app/
    ai/          provider protocol, HTTP transport, tool definitions, the model loop
    data/        Room entities, DAOs, repositories, the credential vault
    docker/      container CLI client and parsers
    monitor/     /proc parsers and the sampling client
    sftp/        SFTP client and the SAF transfer bridge
    ssh/         session, host-key verification, key parsing
    terminal/    VT parser, screen buffer, renderer, key bar
    ui/          Compose screens and view models
```

## License

Not yet chosen. Treat the code as all rights reserved until a licence is added.
