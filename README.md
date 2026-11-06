# Tui SSH

Ứng dụng SSH cho Android, viết bằng Kotlin và Jetpack Compose: terminal VT, trình duyệt
SFTP, xem tài nguyên máy chủ, quản lý container, và trợ lý AI có thể thao tác trên phiên
kết nối.

[English below](#english)

---

## Tính năng

- **Terminal** — giả lập VT100/xterm với pty, 5000 dòng lịch sử, và thanh phím cho những
  phím bàn phím điện thoại không có (Ctrl, Alt, Esc, mũi tên, Ctrl-C).
- **SFTP** — duyệt, tải lên và tải xuống qua Storage Access Framework, kèm tạo thư mục,
  đổi tên, đổi quyền và xoá.
- **Tài nguyên** — CPU, bộ nhớ, ổ đĩa và tiến trình đọc từ `/proc` và `df`.
- **Container** — Docker và Podman: liệt kê, chạy, dừng, khởi động lại, xem log.
- **Trợ lý AI** — chat với nhà cung cấp tương thích OpenAI hoặc Anthropic; model gọi được
  công cụ đọc tệp, đọc tài nguyên, đọc container, và **chỉ chạy lệnh shell khi bạn duyệt**.
- **Bảo mật** — khoá máy chủ được kiểm tra bằng dấu vân tay trên chính khối khoá, không
  phải trên nhãn thuật toán; thông tin đăng nhập mã hoá bằng Android Keystore; mật khẩu và
  cụm mật khẩu chỉ giữ trong RAM, tối đa 15 phút.

**Tiếng Việt là ngôn ngữ mặc định**, có sẵn tiếng Anh.

## Yêu cầu

- JDK 21 (Gradle 8.9 không chạy trên JDK mới hơn)
- Android SDK với platform 35, đã đặt biến `ANDROID_HOME`
- minSdk 26, targetSdk 35

## Build

```bash
cd neo-android
./gradlew testDebugUnitTest assembleDebug
```

APK nằm ở `app/build/outputs/apk/debug/`.

Trên Windows, trỏ rõ JDK 21: bản JDK đi kèm Android Studio mới thường quá mới so với
Gradle 8.9.

## Kiểm thử

Bộ test chạy offline: **330 test** unit và tích hợp, không cần mạng. SSH, SFTP và xác thực
bằng khoá chạy với một SSH server Apache MINA dựng ngay trong tiến trình.

Những test cần hạ tầng thật sẽ **tự bỏ qua** nếu không có biến môi trường — nghĩa là người
fork repo không cần bí mật gì vẫn build xanh.

```bash
# Máy chủ Linux thật, cho các suite về tài nguyên và container
export LIVE_HOST=may-chu-cua-ban
export LIVE_USER=ubuntu
export LIVE_KEY_PATH=/duong/dan/khoa-rieng

# Nhà cung cấp AI thật, cho các suite về trợ lý
export AI_TEST_KEY=khoa-api-cua-ban
export AI_TEST_BASE=https://api.openai.com/v1
export AI_TEST_MODEL=gpt-4o

./gradlew testDebugUnitTest
```

## Cấu trúc mã nguồn

```
neo-android/
  app/src/main/java/com/tuissh/app/
    ai/          giao thức nhà cung cấp, HTTP, định nghĩa công cụ, vòng lặp model
    data/        entity Room, DAO, repository, két mã hoá thông tin đăng nhập
    docker/      client CLI container và bộ phân tích
    monitor/     bộ phân tích /proc và client lấy mẫu
    mosh/        khởi động mosh-server và đọc thông tin kết nối
    sftp/        client SFTP, cầu nối SAF, hàng đợi truyền tệp
    ssh/         phiên, kiểm tra khoá máy chủ, phân tích khoá
    terminal/    bộ phân tích VT, buffer màn hình, renderer, thanh phím
    ui/          màn hình Compose và view model
```

## Giấy phép

Chưa chọn. Hãy coi mã nguồn là bảo lưu mọi quyền cho tới khi có giấy phép.

---

<a id="english"></a>

## English

An Android SSH client in Kotlin and Jetpack Compose: a VT terminal, an SFTP browser,
host metrics, container management and an AI assistant that can act on the session.

### Features

- **Terminal** — VT100/xterm emulation with a pty, 5000 lines of scrollback, and a key
  bar for the keys a phone keyboard lacks (Ctrl, Alt, Esc, arrows, Ctrl-C).
- **SFTP** — browse, upload and download through the Storage Access Framework, plus
  create, rename, chmod and delete.
- **Metrics** — CPU, memory, disks and top processes read from `/proc` and `df`.
- **Containers** — Docker and Podman: list, start, stop, restart, read logs.
- **Assistant** — chat with an OpenAI-compatible or Anthropic-compatible provider; the
  model can call tools that read files, metrics and containers, and can run a shell
  command only after you approve it.
- **Security** — host keys are verified against a fingerprint of the real key blob, not
  a label; credentials are encrypted with the Android Keystore; passwords and
  passphrases are cached in memory only, for 15 minutes.

Vietnamese is the default language, with English available.

### Requirements

- JDK 21 (Gradle 8.9 does not run on newer JDKs)
- Android SDK with platform 35, `ANDROID_HOME` set
- minSdk 26, targetSdk 35

### Build

```bash
cd neo-android
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

On Windows, point at JDK 21 explicitly: the JDK bundled with recent Android Studio is
too new for Gradle 8.9.

### Tests

The suite is offline by default: 330 unit and integration tests that need no network.
SSH, SFTP and key auth run against an in-process Apache MINA SSHD server.

Anything requiring real infrastructure is skipped unless you provide it:

```bash
export LIVE_HOST=your-server
export LIVE_USER=ubuntu
export LIVE_KEY_PATH=/path/to/private-key

export AI_TEST_KEY=your-api-key
export AI_TEST_BASE=https://api.openai.com/v1
export AI_TEST_MODEL=gpt-4o

./gradlew testDebugUnitTest
```

### Layout

Same as the Vietnamese section above.

### Licence

Not yet chosen. Treat the code as all rights reserved until a licence is added.
