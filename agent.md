# AGENT.md — NeoServer Android Rewrite

> Tài liệu sống cho agent/người tiếp tục dự án. Đọc file này TRƯỚC khi sửa code.
> Cập nhật lần cuối: round 3 — 2026-11-06.

---

## 1. Nguồn gốc & mục tiêu

| Mục | Giá trị |
|---|---|
| Artifact gốc | `E:\Code\NeoServer\NeoServer SSH.ipa` (23.453.438 bytes) |
| Bundle ID | `com.neo.NeoMan` |
| Version | 8.4.8 (build 1253) |
| Min iOS | 16.4 · SDK iphoneos27.2 · Xcode 2720 |
| Kiến trúc | Mach-O **arm64**, filetype=2 (EXECUTE), 98 load commands |
| Binary chính | `Payload/NeoServer.app/NeoServer` — 27.164.176 bytes |
| Mã hoá | `LC_ENCRYPTION_INFO_64` cryptid=**0** → **đã giải mã sẵn**, không phải FairPlay |
| Ghi chú | Info.plist có `DecryptedBy = @FastDecryptBot` — bản đã bóc |
| Đích | Android app 100% Kotlin, namespace `com.neomaker.neoserver` |

**Đây là app gì:** NeoServer SSH Client Terminal — SSH/SFTP/Mosh/Docker client + server monitor + AI agent, app thương mại trên App Store (id6448362669), tác giả makerneo.com.

---

## 2. Kiến trúc app gốc (đã bóc)

### 2.1 Tầng ngôn ngữ

```
┌─────────────────────────────────────────────────────────┐
│  SwiftUI (UI)  ·  UIKit (terminal render, editor)        │  ~3100 ký hiệu SwiftUI
├─────────────────────────────────────────────────────────┤
│  Swift core (177 class + 34 + 28 + 26 + 20 + 12 + 11)    │
├─────────────────────────────────────────────────────────┤
│  FFI bridge `Shout` (RusshSessionBridge)                 │
├─────────────────────────────────────────────────────────┤
│  Rust static lib  ·  russh 0.62.6 + tokio 1.53.1         │
│  + russh-sftp 2.4.0 + aws-lc-rs 1.18.0 + ssh-key 0.7.0   │
│  + ml-kem (post-quantum KEX) + curve25519-dalek 5.0.0    │
├─────────────────────────────────────────────────────────┤
│  libssh2 (chỉ còn cho legacy path / một số helper)       │
└─────────────────────────────────────────────────────────┘
```

**Quan trọng:** SSH thật sự do **Rust russh** làm, KHÔNG phải libssh2. `libssh2` chỉ còn 2 string tham chiếu (copyright + tên), nhiều khả năng là dependency của `russh` hoặc dead code.

### 2.2 Danh sách module first-party

| Module | Class | File nguồn đã thấy (DWARF path leak) | Vai trò |
|---|---|---|---|
| `NeoServer` | **177** | 30 file lộ tên | UI, view model, Docker, monitor, AI |
| `NeoSwiftLib` | 34 | NeoUtilLib, StatusAppIconView, CapsuleTagBar, PipifyViewModifier, View+Changes | Base VM, network, cache, ping, Bonjour |
| `TermLib` | 28 | NeoTerminalView, NeoTerminalLoupeView, NeoTerminalImagePreviewController | Terminal UIKit layer, Kitty image, loupe |
| `EditorLib` | 26 | NativeTextEditor, NativeSyntaxRendering, NativeTextPreviewView | Editor + syntax highlight + hex viewer |
| `MoshTerminal` | 20 | — | **VT emulator + prediction + overlay** |
| `SQLite` | 12 | Connection, Statement, Query, SchemaReader, SchemaChanger, Coding, Backup, FTS4/5 | SQLite wrapper tự viết |
| `Shout` | 11 | — | FFI cầu sang Rust |
| `SSHKeyFen` | 4 | — | PPKConverter, DERReader, BinaryDataReader |
| `MoshCore` / `MoshTransport` / `MoshBootstrap` | 7 | MoshClientSession, UDPTransport, MoshBootstrap | Giao thức Mosh |
| `SymbolPicker` | 3 | Symbol, Symbols, SymbolPicker | Chọn icon SF Symbols |
| `MarkdownUI` / `MarkdownCore` | 11 | — | Render markdown cho AI chat |
| `Firebase*` / `GoogleAppMeasurement` | — | — | Analytics + crash + ads attribution |

### 2.3 Rust crates (49, đã bóc chính xác từ path)

```
aes 0.9.2 · argon2 0.6.0-rc.8 · aws-lc-rs 1.18.0 · base64 0.22.1
bcrypt-pbkdf 0.11.0 · bit-vec 0.9.1 · block-buffer 0.12.1 · block-padding 0.4.2
byteorder 1.5.0 · bytes 1.12.1 · cipher 0.5.2 · const-oid 0.10.2
cpufeatures 0.3.0 · crypto-bigint 0.7.5 · ctutils 0.4.2 · curve25519-dalek 5.0.0
dashmap 6.2.1 · data-encoding 2.11.1 · der 0.8.1 · dsa 0.7.0
futures-util 0.3.34 · hashbrown 0.14.5 · hex 0.4.3 · hybrid-array 0.4.14
internal-russh-num-bigint 0.5.0 · itoa 1.0.18 · miniz_oxide 0.8.9 · ml-kem 0.3.2
module-lattice 0.2.3 · num-integer 0.1.47 · once_cell 1.21.4 · parking_lot_core 0.9.12
pkcs5 0.8.1 · rand 0.10.2 · rfc6979 0.6.0 · rsa 0.10.0-rc.18 · russh 0.62.6
russh-cryptovec 0.62.0 · russh-sftp 2.4.0 · russh-util 0.52.0 · scrypt 0.12.0
sec1 0.8.1 · serde_json 1.0.151 · smallvec 1.15.2 · ssh-key 0.7.0-rc.11
subtle 2.6.1 · tokio 1.53.1 · tokio-util 0.7.19 · wnaf 0.14.0
```

→ Bản Android dùng **JSch (mwiede fork)** vì tương đương chức năng và không cần toolchain Rust. Xem `check.md` mục "chưa port".

---

## 3. Cấu trúc dự án Android

```
E:\Code\NeoServer\neo-android\
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle\libs.versions.toml
├── agent.md              ← file này
├── plan.md               ← lộ trình
├── check.md              ← báo cáo tiến độ
└── app\
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src\main\java\com\neomaker\neoserver\
        ├── core\                 NeoLog, NeoResult/NeoError, NeoFormat, NeoSupport,
        │                         AtomicBool, Hashes, PortRange, Truncate
        ├── data\
        │   ├── NeoDatabase.kt    Room DB v1, 16 entity, seed theme
        │   ├── model\            ServerEntities, AiAndMetricEntities
        │   ├── dao\              ServerDao, SupportDao
        │   └── crypto\           CredentialVault (AES-GCM + Android Keystore)
        ├── ssh\
        │   └── key\              KeyParser, SshBinaryReader(DER), AesCtrCipher
        └── terminal\
            ├── ansi\             Cell, TerminalRow, TerminalBuffer
            ├── parse\            VtParser (DEC PDT state machine), VtEmulator
            └── builtin\          BuiltInThemes (10 theme)
```

### 3.1 Quy ước code

- **Tên class giữ nguyên gốc Swift** khi có thể (`NeoTerminalView` → `NeoTerminalView`, `ForwardModel` → `ForwardEntity`).
- Enum/constant dùng `UPPER_SNAKE` hoặc `const val` trong `companion object`.
- Không dùng `!!` ngoài chỗ đã chứng minh non-null.
- Mọi lỗi đi qua `NeoResult<T>` + `NeoError`; không throw ra UI.
- Log qua `NeoLog` với tag trong `LogTag`.
- Chuỗi hiển thị phải khớp key trong `Localizable.strings` gốc (xem mục 4).

---

## 4. Bản địa hoá

Đã bóc toàn bộ 12 ngôn ngữ từ `.lproj/Localizable.strings` (định dạng `bplist00`):

| Ngôn ngữ | Số key |
|---|---|
| en | 456 |
| vi | 3590 |
| de | 3591 |
| es | 3591 |
| fr | 3591 |
| ja | 3592 |
| ko | 3591 |
| pt-BR | 3590 |
| ru | 3592 |
| tr | 3591 |
| zh-Hans | 3600 |
| zh-Hant | 3590 |

File JSON đã trích: `E:\Code\NeoServer\spec\locales_en.json`, `locales_vi.json`.

**Lưu ý:** `en` chỉ có 456 key vì phần lớn chuỗi dùng literal trực tiếp trong Swift (không qua `NSLocalizedString`). Android cần `strings.xml` riêng — xem `check.md`.

---

## 5. Bản đồ tính năng (feature → code)

| Tính năng | Swift gốc | Kotlin đích | Trạng thái |
|---|---|---|---|
| Server CRUD | `SSHServerModel` | `ServerEntity` + `ServerDao` | ✅ entity + dao |
| Nhóm server | `SSHServerGroupModel` | `ServerGroupEntity` | ✅ |
| Private key | `SSHPrivateKeyModel`, `SSHKeyFen` | `PrivateKeyEntity`, `KeyParser` | ✅ parse |
| Known hosts | `KnownHostStore`, `KnownHostVerificationManager` | `KnownHostEntity` | ✅ |
| Proxy | `ProxyModel` | `ProxyEntity` | ✅ |
| Port forward | `ForwardModel`, `TcpForwardManager` | `ForwardEntity` | ✅ |
| Script | `ScriptModel`, `ScriptGroupModel` | `ScriptEntity`, `ScriptRunEntity` | ✅ |
| Theme | `TerminalCustomThemeModel` | `TerminalThemeEntity` + `BuiltInThemes` | ✅ |
| Highlight | `HighlightRuleModel` | `HighlightRuleEntity` | ✅ |
| SFTP | `SFTPBrowserViewModel`, `SFTPTransferCenter` | — | ⏳ |
| Terminal | `NeoTerminal`, `VTEmulator` | `VtParser`, `VtEmulator`, `TerminalBuffer` | ✅ core |
| Mosh | `MoshCore/Transport/Bootstrap` | — | ❌ chưa |
| Docker | `ContainerServerViewModel` v.v. | — | ⏳ |
| Monitor | `CPU/MCPU/MDf/MNet/OsRelease/Top` | — | ⏳ |
| AI chat | `AIChatViewModel`, `AIServiceProviderModel` | `AiProviderEntity` v.v. | ✅ model |
| Skills | `AISkillsStore` | `AiSkillEntity` | ✅ |
| AI browser | `AIBrowserSession`, `AIBrowserTab` | `AiBrowserTabEntity` | ✅ |
| Wake-on-LAN | `WakeOnLAN` | — | ⏳ |
| Bonjour | `BonjourServiceFinder` | — | ⏳ |
| Push | `PushNotificationCenterModel` | — | ⏳ |

Ký hiệu: ✅ xong · ⏳ đang dở/tiếp theo · ❌ chưa bắt đầu

---

## 6. Tham chiếu kỹ thuật đã bóc

### 6.1 Endpoint AI
```
https://chatgpt.com/backend-api/codex
https://api.openai.com/v1
https://auth.openai.com/oauth/authorize
https://auth.openai.com/oauth/token
http://localhost:1455/auth/callback        ← loopback OAuth callback
https://api.deepseek.com/v1
https://openrouter.ai/api/v1
https://api.siliconflow.cn/v1
https://api.anthropic.com
https://open.bigmodel.cn/api/paas/v4
https://open.bigmodel.cn/api/anthropic
```

### 6.2 Mosh
```
mosh-server new -s -c 256 -l LANG=en_US.UTF-8
UDP port range mặc định: 60000-61000
Định dạng output: "MOSH CONNECT <port> <key>"
```

### 6.3 Docker
```
PATH tiền tố: /opt/homebrew/bin:/opt/podman/bin:/bin:/opt/bin:/opt/sbin:/root/bin:
  /sbin:/usr/bin:/usr/bin/X11:/usr/games:/usr/local/bin:/usr/local/games:
  /usr/local/sbin:/usr/sbin:/usr/syno/bin:/usr/syno/sbin:
  /share/CACHEDEV1_DATA/.qpkg/container-station/bin
docker info --format '{{json .}}'
image ls --no-trunc --format '{{json .}}'
stats --no-stream --no-trunc --format '{{json .}}'
history --no-trunc --format '{{json .}}'
compose ls -a --format json
events --since 24h --stream=false --format json | tail -n 300
podman info --format=json
```

### 6.4 Monitor — regex đã bóc
```
CPU usage:\s+([0-9.]+)%\s+user,\s+([0-9.]+)%\s+sys,\s+([0-9.]+)%\s+idle
packets:\s(\d+)/(\d+[a-zA-Z])\s+in,\s+(\d+)/(\d+[a-zA-Z])\s+out
Disks: (\d+)/(\d+[a-zA-Z]) read, (\d+)/(\d+[a-zA-Z]) written
(\d+\.?\d*)%?\s+id
System-wide memory free percentage: (\d+)%
hw\.physmem:\s*(\d+)
vm\.stats\.vm\.v_page_count:\s*(\d+)
vm\.stats\.vm\.v_free_count:\s*(\d+)
(<uptime 4 biến thể Darwin: min / h:mm / day,h:mm / day,min>)
```

### 6.5 Lệnh monitor
```
cat /etc/os-release | grep PRETTY_NAME;uname -a
cat /proc/stat | grep cpu
cat /proc/meminfo
cat /proc/uptime
cat /proc/loadavg
cat /proc/net/route | awk '$2 == "00000000" {print $1}' | head -n 1
command df -kT || command df -Y || command df -k
export LANG=en_US.utf-8 ; export COLUMNS=1000;top -n 1 -b -c || top -n 1 -b
export LANG=en_US.utf-8 ; export COLUMNS=1000;ps -ax -o pid,stat,%cpu,%mem,user,pri,nice,vsz,rss,time,command
nvidia-smi -q -x | grep -v MHz
sensors | grep 'Core' | awk '{print $3}'
```

### 6.6 Cảnh báo an toàn (regex destructive-command)
App gốc chặn các lệnh này trước khi AI thực thi:
```
\brm\s+(-[a-z]*[rf][a-z]*\s+)+
\bchmod\s+-[a-z]*r[a-z]*\s+0?777\b
\bdrop\s+(database|table|schema)\b
\btruncate\s+(table\s+)?\w
\bdocker\s+(system|volume|image|container)\s+prune\b
\bdocker\s+(rm|rmi)\s+.*-f\b
\bdocker\s+compose\s+down\b.*(-v|--volumes)
\bkill(all)?\s+-(9|kill)\b
\bsystemctl\s+(stop|disable|mask)\b
\biptables\s+-(f|x)\b
\bgit\s+(push\s+.*--force|push\s+.*-f\b|reset\s+--hard|clean\s+-[a-z]*f)
```

### 6.7 AI tool schema (bóc từ system prompt)
```
execute        — chạy shell trên server đã chọn (có server_id)
inspect        — chỉ đọc, dùng khi Plan mode
read_file      — đọc file
write_file     — ghi file trong workdir
list_dir       — liệt kê thư mục
upload_file    — SFTP upload (local_path tương đối workdir → remote_path tuyệt đối)
download_file  — SFTP download về AIChat/Downloads
browser_*      — open / close_tab / list_tabs / switch_tab / run_javascript / inspect / reset / execute
get_skill      — nạp SKILL.md theo tên
create_skill   — tạo/cập nhật skill
sleep          — pause cục bộ 0..300s (không chạy gì trên server)
```
Quy tắc bóc được:
- Không được claim thành công nếu kết quả không xác nhận.
- Không bao giờ lộ password/key/token trong kết quả tool.
- Plan mode: không mutate.
- Nhiều server: phải có `server_id` cho thao tác mutate.
- `neopath` = placeholder đường dẫn file/folder đang chọn, tự quote.

### 6.8 File resource trong bundle
```
browser-snapshot.js   — DOM → text outline cho AI browser tool
skill-creator.md      — hướng dẫn tạo SKILL.md
JetBrainsMonoNerdFontMono-Regular.ttf — font terminal
default.metallib      — shader Metal (terminal background)
```

---

## 7. Quyết định kiến trúc

| # | Quyết định | Lý do | Hệ quả |
|---|---|---|---|
| D1 | Dùng **JSch mwiede** thay vì bind russh | Không cần Rust toolchain/NDK; JSch hỗ trợ ed25519, rsa-sha2, aes-gcm | Mất `ml-kem` post-quantum KEX; mất vài option russh-only |
| D2 | Room thay SQLite wrapper tự viết | Wrapper gốc là 12 class không mang giá trị riêng | Schema mới, phải viết migration tay nếu import từ iOS |
| D3 | **Port VtEmulator sang Kotlin** thay vì dùng Termux lib | Giữ đúng hành vi `MoshPredictionEngine`, overlay, Kitty | Tốn công nhưng không phụ thuộc lib ngoài |
| D4 | Bỏ iCloud sync | Android không có tương đương | Thay bằng Google Drive AppData/WebDAV (chưa làm) |
| D5 | Mosh: **chưa làm**, để cuối | Cần OCB-AES + SSP state sync; khối lượng lớn | `useMosh` flag có trong DB nhưng engine chưa nối |
| D6 | Firebase → không port | Analytics/crash của bên thứ ba, không phải lõi | Không có telemetry |
| D7 | `reStructured` tên class theo tiếng Anh | Tránh nhầm với bản gốc | Tên hơi khác: `ServerModel` → `ServerEntity` |

---

## 8. Đọc thêm

- `plan.md` — 6 giai đoạn, thứ tự thi công, tiêu chí done.
- `check.md` — báo cáo: đã làm / chưa làm / chưa giải mã / chưa dịch.

---

## 9. Cách chạy

```powershell
cd E:\Code\NeoServer\neo-android
gradle wrapper --gradle-version 8.9      # lần đầu
.\gradlew assembleDebug
.\gradlew installDebug
```

Yêu cầu: JDK 17+, Android SDK 35, minSdk 26.

---

## 10. Cảnh báo khi sửa

1. **Không** đổi `Cell` packing format — `TerminalRow`, `VtEmulator`, renderer phụ thuộc bit layout.
2. `VtParser` là state machine DEC PDT. Mọi thay đổi phải chạy lại test escape sequence.
3. `NeoDatabase.version` tăng thì phải viết `Migration`, không dùng `fallbackToDestructiveMigration` ở release.
4. `CredentialVault` phụ thuộc Android Keystore — không chạy được trên unit test JVM, chỉ instrumented test.
5. Tag `LogTag.*` được `check.md` tham chiếu; đổi tên thì sửa cả hai.
