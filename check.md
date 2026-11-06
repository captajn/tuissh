# CHECK.md — Báo cáo tiến độ

> Cập nhật: round 20 — 2026-11-06 20:44 (Asia/Saigon)
> Quy ước: ✅ xong · 🟡 một phần · ⏳ chưa làm · ❌ không làm được / bỏ · 🔒 chưa giải mã

---

## TRẠNG THÁI HIỆN TẠI

```
BUILD SUCCESSFUL
271/271 test pass  ·  0 failures
93 file Kotlin  ·  18 file test
APK 20,133,519 bytes
```

**AI chat chạy thật trên emulator** — chat, tool calling, card tool, đều có bằng chứng.

---

## CỘT MỐC

| Mốc | Thời điểm | Bằng chứng |
|---|---|---|
| `BUILD SUCCESSFUL` lần đầu | 01:03 | APK 19.603.000 bytes |
| Chạy trên emulator thật | 01:28 | `_shots/01_launch.png`…`05_terminal.png` |
| SSH kết nối thật | 01:44 | `SshSessionIntegrationTest` 7/7 |
| Đổi tên Tui SSH + logo | 01:45 | `_shots/11_tuissh.png` |
| Tiếng Việt mặc định | 01:49 | `_shots/15_vi_default.png` |
| Terminal render đúng | 11:14 | `_shots/43_final.png` |
| Upload + download qua SAF | 12:38 | `_shots/53_uploaded.png` |
| Key bar + Ctrl-C | 12:59 | `_shots/61_ctrlc.png` |
| E2E: password → host key → terminal | 15:17 | `_shots/81_terminal_ok.png` |
| E2E: SFTP có xác thực | 16:02 | `_shots/87_sftp_final.png` |
| Theme tím than + 5 tab | 18:30 | `_shots/93_shell_fixed.png` |
| Monitor client + tab Chỉ số | 19:52 | `uiautomator` |
| AI gọi API thật (chat + tool + stream) | 20:16 | `AiLiveTest` 5/5 |
| **AI chat UI + tool card trong app** | **20:42** | `uiautomator`: `system_info 1,2s`, `docker_ps 0,1s`, log `round 1 → round 2` |

---

## TỔNG QUAN

| Hạng mục | Xong | Một phần | Chưa | Bỏ |
|---|---:|---:|---:|---:|
| Tầng core | 8 | 0 | 0 | 0 |
| Tầng data | 7 | 0 | 0 | 0 |
| SSH | 10 | 0 | 0 | 0 |
| SFTP | 7 | 0 | 0 | 0 |
| Terminal | 7 | 1 | 0 | 0 |
| Host key | 3 | 0 | 0 | 0 |
| Auth UI | 3 | 0 | 0 | 0 |
| Docker | 3 | 0 | 1 | 0 |
| Monitor | 3 | 0 | 0 | 0 |
| **AI** | **8** | 0 | 0 | 0 |
| Mosh | 0 | 0 | 4 | 0 |
| **UI / Theme** | 9 | 2 | 8 | 0 |
| Hạ tầng Android | 5 | 0 | 4 | 0 |
| Bản địa hoá | 5 | 0 | 1 | 0 |

---

## AI TOOL THỰC THI TRÊN VPS THẬT (round 23)

Đây là **bằng chứng đầy đủ nhất từ đầu dự án**: model gọi tool, tool đọc dữ liệu thật,
model trả lời tiếng Việt dựa trên dữ liệu đó.

### Transcript thật (chạy trên VPS, không phải fixture)

```
=== TOOL CALLS ===
  started  docker_ps {}
  finished docker_ps ok=true '10 container' 766ms

=== ANSWER ===
Có 10 container, 8 đang chạy (eager_galois, tdlight, dtk-api-1, dtk-worker-1,
dtk-redis-1, dtk-postgres-1, dtk-browser-rpc-1, dtk-downloader-1) và 2 đã dừng
(dtk-migrate-1, flamboyant_cannon).

=== ROUNDS ===
2
```

Model **đếm đúng 8 chạy / 2 dừng** — không bịa. Chuỗi đầy đủ:
SSH key auth → `docker ps --format '{{json .}}'` → JSON parse → model → tiếng Việt.

### 6 test với hạ tầng thật (`AiToolLiveTest`)

| Test | Kết quả |
|---|---|
| `system_info returns real numbers from a real host` | **PASS** |
| `docker_ps returns the containers that actually exist` | **PASS** |
| `docker_logs returns output for a running container` | **PASS** |
| `run_command is refused without approval` | **PASS** |
| `run_command executes once approved` | **PASS** |
| `the model answers a host question using real tool output` | **PASS** |

Test kiểm **giá trị**, không chỉ "không crash":
- `system_info` payload phải chứa `host:`, `memory:`, `disks:`
- `docker_ps` count phải `> 0` (host có container thật) và có `[running]`
- `run_command` không duyệt → payload chứa `declined` và **không chạy gì**
- `run_command` có duyệt → stdout chứa chuỗi mình gửi vào
- Full turn: model phải gọi tool, tool phải `ok=true`, câu trả lời không rỗng

### Test sai của mình (lần thứ 4)

`docker_ps` assert `payload.contains("image")` — nhưng output thật là
`2f4f756faf3a eager_galois [running] python:3.13-slim ports=…`, không có chữ "image".
Test fail dù tool trả **dữ liệu hoàn toàn đúng**. Đổi sang kiểm `count > 0` + có
`[running]` + có `:` trong image tag.

---
## SCROLLBACK (round 22)

Terminal đã có 5000 dòng scrollback từ lâu nhưng **không có cách xem**. Round này nối
cử chỉ và sửa hai lỗi trong chính phần vừa viết.

| File | Thay đổi |
|---|---|
| `terminal/render/NeoTerminalView.kt` | `scrollOffset` state. Kéo dọc để cuộn lịch sử, chạm để chọn. `rowAt()` pha trộn scrollback và màn hình sống. Cursor **ẩn khi đang xem lịch sử** |
| `ui/TerminalScreen.kt` | Banner vàng "Đang xem lịch sử · N dòng" khi cuộn lên |
| `terminal/ScrollbackMappingTest.kt` | **10 test** cho ánh xạ dòng |

### Hai lỗi trong code mình vừa viết

| # | Lỗi | Sửa |
|---|---|---|
| B36 | Cursor vẽ sai chỗ khi cuộn — `cursorRow` là toạ độ màn hình sống, không trừ offset | Ẩn cursor khi `offset > 0`: nó thuộc màn hình sống, vẽ ở dòng đang hiện output cũ là vô nghĩa |
| B37 | `onDragEnd` có comment "snap về cuối" nhưng code `if (scrollOffset < 0)` **không bao giờ đúng** vì đã `coerceIn(0, …)` | Đổi thành `if (scrollOffset <= 1) scrollOffset = 0` — thả trong vòng một dòng nghĩa là không định đọc lịch sử |

### Test sai của mình (không phải bug code)

Fixture ban đầu gọi `scrollUp(1)` sau **mỗi** lần ghi, nên cả 10 dòng vào scrollback.
Thực tế terminal chỉ cuộn khi màn hình đầy. Sửa fixture cho đúng: ghi đầy 4 dòng rồi
mới cuộn. Sau đó lộ ra chi tiết nữa — lần ghi **gây ra** cuộn cũng nằm trên dòng bị đẩy
đi, nên 10 dòng ghi vào màn 4 dòng → **7** dòng vào lịch sử, không phải 6.

Test kiểm:
- Offset 0 = màn hình sống nguyên vẹn
- Cuộn 1 dòng = dòng lịch sử mới nhất
- Cuộn hết = dòng cũ nhất còn giữ
- **Đường nối giữa lịch sử và màn sống không trùng dòng nào**
- Overscroll kẹp về dòng cũ nhất, không hiện dòng trắng
- Alternate screen (`vim`, `htop`) không thêm scrollback

---
## CHỨNG MINH TRÊN VPS LINUX THẬT (round 21)

He cung cấp VPS và lần đầu mình chạy được với dữ liệu thật.

**Máy**: VPS Oracle Cloud · Ubuntu 26.04 LTS · aarch64 · 2 CPU · 12GB RAM
**Docker**: 29.1.3, container thật `tdlight` (healthy, port 8081) và `eager_galois`

| Test | Kết quả |
|---|---|
| `metrics come back from a real Linux host` | **PASS** |
| `docker containers come back from a real host` | **PASS** |
| `docker logs come back for a running container` | **PASS** |
| `docker images and volumes parse from a real host` | **PASS** |
| `kill of a non-existent pid is refused` | **PASS** |

Test kiểm **giá trị**, không chỉ "không crash":
- `memory.totalKb > 0` và `usedKb <= totalKb`
- CPU% sau warm-up nằm trong `0..100` (không phải 0.0 giả)
- `disks.any { it.mount == "/" }` — tmpfs bị lọc đúng
- `containers.all { it.shortId.length == 12 }`
- `networks.any { it.name == "bridge" }`
- Port 8081 của `tdlight` parse đúng

**Cách chạy lại:**
```powershell
$env:LIVE_HOST='<ip>'
$env:LIVE_USER='ubuntu'
$env:LIVE_KEY_PATH='<path to key>'
.\gradlew.bat testDebugUnitTest --tests '*MonitorLiveTest*' --no-daemon
```

Key đọc từ **path**, không nhúng vào source. Không có biến môi trường thì test **skip**.

---

## B35 — BUG NGHIÊM TRỌNG NHẤT: xác thực private key chưa từng hoạt động

| Vấn đề | Chi tiết |
|---|---|
| Triệu chứng | Kết nối VPS bằng key → `AuthFailed` dù `ssh -i key` từ CLI vào được |
| Nguyên nhân | `SshSession.connect()` **không gọi `jsch.addIdentity()`**. JSch chỉ chào `password` và `keyboard-interactive`, không bao giờ dùng key |
| Vì sao 271 test không bắt được | `TestSshServer` chỉ có `passwordAuthenticator` — **không có** `publickeyAuthenticator`. Toàn bộ test SSH đều dùng password |
| Sửa | `jsch.addIdentity(name, pemBytes, null, passphrase)` trước khi tạo session; thêm `PubkeyAcceptedAlgorithms` gồm `rsa-sha2-512/256` vì Ubuntu 22.04+ tắt `ssh-rsa` SHA-1 |
| Regression test | `SshKeyAuthTest` **4 test** — server test giờ nhận key, và có ca "key lạ bị từ chối" |
| Ảnh hưởng thực tế | **Mọi người dùng chỉ dùng key-only đều không đăng nhập được.** Đây là lỗi chặn hoàn toàn, không phải lỗi khó chịu |

**Bài học:** test offline chỉ chứng minh code khớp với *giả định của mình*. Test server
của mình không hỗ trợ key auth, nên không có test nào chạm vào đường đó. Phải chạy với
hạ tầng thật mới lộ.

---
## AI CHAT UI (round 20)

| File | Nội dung |
|---|---|
| `ui/ai/AiChatViewModel.kt` ✅ | Transcript append-only. `Row.User/Assistant/Tool/Notice`. Streaming chỉ sửa bubble cuối. `approve()` treo coroutine cho tới khi người dùng trả lời |
| `ui/ai/AiChatScreen.kt` ✅ | Bubble user tím phải, assistant nền xám, **card tool** có chấm trạng thái + thời gian + expand. Nút gửi vàng, nút dừng khi đang chạy |
| `ui/ai/AiProviderDialog.kt` ✅ | Nhập base URL / model / key. Key đi thẳng vào `CredentialVault`, dialog **không đọc lại** |
| `ui/NeoApp.kt` ✅ | Tab **Thêm** giờ là AI chat. Key mã hoá trong `SharedPreferences` + vault. SFTP optional (host không có SFTP vẫn dùng được tool khác) |

### Bằng chứng chạy thật (`uiautomator dump`)

```
Tra                                          ← tin nhắn user
I'm not sure what you mean — "Tra" ...       ← AI trả lời thật
If it helps: I'm connected to `neo@10.0.2.2` and can check files,
system stats, or Docker containers there.
listsysteminfo                               ← tin nhắn thứ hai
system_info    1,2s    không đọc được số liệu     ← CARD TOOL
docker_ps      0,1s    không đọc được container   ← CARD TOOL
```

Log: `round 1 requested 1 tool call(s)` → `round 2 requested 1 tool call(s)`

**Nghĩa là:** model gọi tool → app chạy qua SSH → gửi kết quả lại → model gọi tool
tiếp → hết. Đúng vòng lặp của app gốc. Hai tool báo lỗi là **đúng** vì server test là
Windows, không có `/proc` và không có Docker.

## LỖI MÔI TRƯỜNG TEST (không phải bug app)

`adb shell input text "có dấu cách"` làm **crash** Android shell:
```
java.lang.NullPointerException: Attempt to get length of null array
  at InputShellCommand.sendText
```
Nên tin nhắn đầu chỉ vào được chữ "Tra". Đây là giới hạn công cụ test, không phải app.
Cách gõ có dấu cách trên emulator: dùng `%s` — nhưng bản này cũng NPE. Cần
`uiautomator` hoặc ADB keyboard riêng nếu muốn chứng minh câu dài.

## CÒN THIẾU

| Việc | Ghi chú |
|---|---|
| Chứng minh tool **thành công** | Cần server Linux: `system_info`, `docker_ps`, `list_directory` phải trả dữ liệu thật |
| Dialog duyệt `run_command` | Code có, chưa bấm thử trên emulator vì cần câu hỏi dẫn tới lệnh ghi |
| Lưu lịch sử hội thoại | Hiện mất khi đóng app; `AiConversationEntity` đã có trong DB |

---

## PHẦN A — ĐÃ LÀM (có code thật)

### A1. Core (`core/`)

| File | Nội dung | Ghi chú |
|---|---|---|
| `NeoLog.kt` ✅ | Log Android + ring buffer 2000 entry + `LogTag` 10 tag | Thay `NeoSwiftLib/NeoLog.swift` |
| `NeoResult.kt` ✅ | `NeoResult<T>` + 40 nhánh `NeoError` có kiểu | Timeout, AuthFailed, HostKeyChanged, Mosh*, Sftp*, Ai*, Docker* |
| `NeoFormat.kt` ✅ | `bytes()`, `rate()`, `parseSizeToBytes()`, `parsePercent()`, `duration()`, `compactNumber()`, `looksBinary()` | Có cả `UnitBase.BINARY/DECIMAL` như `StringEncoding` gốc |
| `NeoSupport.kt` ✅ | `AtomicBool`, `Hashes` (sha256/md5/sshFingerprint/constantTimeEquals), `PortRange`, `Truncate` | `sshFingerprint` đúng định dạng `SHA256:<b64-nopad>` |

### A2. Data layer (`data/`)

| File | Nội dung |
|---|---|
| `NeoDatabase.kt` ✅ | Room DB v1, **16 entity**, seed theme khi tạo |
| `model/ServerEntities.kt` ✅ | `ServerEntity` (40 field), `ServerGroupEntity`, `PrivateKeyEntity`, `CipherBlobEntity`, `KnownHostEntity`, `ProxyEntity`, `ForwardEntity`, `ScriptEntity`, `ScriptGroupEntity`, `ScriptRunEntity`, `TerminalThemeEntity`, `HighlightRuleEntity`, `RecentSessionEntity`, `SftpRecentLocationEntity`, `SftpCustomMenuEntity` |
| `model/AiAndMetricEntities.kt` ✅ | `AiProviderEntity` (**8 preset** khớp endpoint gốc), `AiModelEntity`, `AiConversationEntity`, `AiMessageEntity`, `AiSkillEntity`, `AiBrowserTabEntity`, `MetricSampleEntity`, `MonitorSettingEntity`, `DockerHostEntity`, `KvEntity` |
| `dao/ServerDao.kt` ✅ | 18 query server + group |
| `dao/SupportDao.kt` ✅ | 11 DAO còn lại, ~60 query |
| `crypto/CredentialVault.kt` ✅ | AES-256-GCM, key trong Android Keystore, format `b64(IV12+ciphertext)` |

**Field `ServerEntity` bám đúng tên reflection bóc được:** `moshServerCommand`, `moshUdpPort`, `bastionServerId`, `keepaliveSeconds`, `agentForwarding`, `macAddress` (WOL), `failoverPort`.

### A3. SSH key (`ssh/key/`)

| File | Nội dung |
|---|---|
| `KeyParser.kt` ✅ | Nhận diện **7 định dạng**: OpenSSH mới, PKCS#1 RSA/EC/DSA, PKCS#8, PKCS#8 encrypted, PPK v1/v2/v3, bare base64. Trả `ParsedKey` có algorithm/bits/fingerprint/publicKey |
| `SshBinaryReader.kt` ✅ | Reader SSH wire (`uint32`+`string`) + DER reader tối giản (SEQUENCE/INTEGER/OID/OCTET STRING) |
| `AesCtrCipher.kt` ✅ | Xác minh passphrase của OpenSSH key bằng 2 checkint; hỗ trợ `aes{128,192,256}-{cbc,ctr}`, `aes{128,256}-gcm@openssh.com`; bcrypt_pbkdf |

### A4. Terminal (`terminal/`)

| File | Nội dung | Độ phủ |
|---|---|---|
| `ansi/Cell.kt` ✅ | Pack 1 cell vào 1 `Int`: kind(4b) + colour(24b) + flags(4b). `paletteToRgb()` xterm-256 | 8 flag style |
| `ansi/TerminalRow.kt` ✅ | `CharArray` + `IntArray fg/bg` + combinator (combining marks). Wide char 2 cell đánh dấu tail | insert/delete/erase/copy |
| `ansi/TerminalBuffer.kt` ✅ | Primary + alternate screen, scrollback 5000 dòng, `textRange()` cho copy | xterm-compatible |
| `parse/VtParser.kt` ✅ | State machine DEC PDT **13 state**, UTF-8 incremental decode, CSI có sub-param (`38:2:r:g:b`), OSC/DCS/SOS-PM-APC | ~1000 dòng |
| `parse/VtEmulator.kt` ✅ | Handler đầy đủ: C0, ESC, CSI, SGR, private mode, DECSCUSR, scroll region, tab stop, charset G0-G3 + DEC Special Graphics, `charWidth()` East-Asian/emoji, OSC 0/1/2/9/52/1337/777/5522, DCS sixel/kitty | ~1100 dòng |
| `builtin/BuiltInThemes.kt` ✅ | 10 theme: xterm, Dracula, One Dark, Nord, Gruvbox, Tokyo Night, Solarized Dark/Light, GitHub Light, Windows Campbell | đúng layout 16 ANSI |

**Đã implement các escape sequence quan trọng:**
`ICH DCH IL DL ECH SU SD ED EL CUU CUD CUF CUB CNL CPL CHA HPA CUP HVP CHT CBT HPR VPA VPR REP DA1 DSR DECSTBM DECSCUSR SGR(0-9,21-29,30-37,38,39,40-47,48,49,90-97,100-107) SCOSC SCORC SU/SD/IND/NEL/RI/HTS/TBC/DECID/RIS/DECALN/DECAWM/DECOM/DECSCNM/1049/DECSET-DECRST`

### A5. Bản địa hoá

✅ Bóc **12 file** `Localizable.strings` (bplist00) → JSON:
`spec/locales_en.json` (456 key), `spec/locales_vi.json` (3590 key), và 10 ngôn ngữ còn lại.

✅ `i18n/NeoLocale.kt` — danh sách 12 ngôn ngữ, **tiếng Việt xếp đầu**, tên bản địa, chuẩn hoá BCP-47, chuyển sang Android resource qualifier.

✅ `res/values-vi/strings.xml` — bản dịch tiếng Việt viết tay: navigation, form server, 15 thông báo lỗi khớp `NeoError`, AI, common.

### A6. Hạ tầng Android (mới ở round 6)

| File | Nội dung |
|---|---|
| `NeoApplication.kt` ✅ | Tạo 3 notification channel (terminal / alerts / transfers) |
| `service/TerminalForegroundService.kt` ✅ | Foreground service giữ socket SSH khi chạy nền |
| `service/MonitorScheduler.kt` ✅ | WorkManager periodic 15 phút |
| `service/MonitorWorker.kt` ✅ | Worker khung (chờ parser monitor) |
| `service/BootReceiver.kt` ✅ | Re-arm lịch sau reboot |
| `ui/NeoActivity.kt` ✅ | Single activity + Compose scaffold |
| `AndroidManifest.xml` ✅ | 12 permission, deep link `neoserver://` và `ssh://` |
| `res/` ✅ | adaptive icon, theme, file_paths, backup rules, strings vi/en |

### A8. SSH runtime (mới round 4)

| File | Nội dung | Trạng thái |
|---|---|---|
| `ssh/SshTypes.kt` ✅ | `SshConnectSpec` (7 field auth), `SshPhase` 11 pha, `SshSessionInfo`, interface `SshConnectPrompts` 6 hàm suspend | xong |
| `ssh/SshSession.kt` ✅ | connect / openShell / exec / resize / disconnect, `UserInfo` bridge, map 8 loại `JSchException` → `NeoError` | xong, **chưa test với server thật** |
| `ssh/hostkey/HostKeyVerifier.kt` 🟡 | TOFU 3 nhánh Trusted/Unknown/Changed | fingerprint hiện dựa trên **label**, không phải blob — xem C12 |
| `ssh/proxy/ProxyChainResolver.kt` ✅ | ProxyJump chain, chống vòng lặp, `MAX_DEPTH=16` | xong |

### A9. Unit test (mới round 4)

| File | Số test | Phủ |
|---|---|---|
| `VtEmulatorTest.kt` | 29 | in ấn, LF/CR/BS/TAB, autowrap, scrollback, ED/EL, CUP/CUU/CUD/CUF, SCOSC/SCORC, ICH/DCH/IL/DL, scroll region, 1049, DECOM, OSC title/bell, DEC Special Graphics, wide char, combining, resize |
| `NeoFormatTest.kt` | 14 | bytes/rate/parseSize/percent/duration/looksBinary/group + `PortRange` 5 case |
| **`SshSessionIntegrationTest.kt`** | **7** | connect thật, sai mật khẩu, host key unknown/changed, shell channel, exec, disconnect |
| **`SftpClientIntegrationTest.kt`** | **14** | list, read/write, mkdir -p, rename, chmod, delete, delete tree, upload 200 KB, download, truncate, disk usage, error paths |

**64/64 pass.** `TestSshServer.kt` dựng **Apache MINA SSHD thật** trong process —
password auth, SFTP subsystem, interactive shell, exec command factory.

### A14. SFTP (mới round 8)

| File | Nội dung |
|---|---|
| `sftp/SftpClient.kt` ✅ | ~20 thao tác: `pwd`, `list`, `readFile`, `download`, `upload`, `writeText`, `mkdir`, `mkdirs`, `remove`, `removeRecursive`, `rename`, `chmod`, `symlink`, `statVfs`. Map 4 mã lỗi SFTP sang `NeoError` |
| `SshSession.openSftp()` ✅ | Mở channel SFTP trên session đã xác thực — không login lần hai |

**Trạng thái:** chạy đúng với server thật, **chưa có UI**. Chưa nối vào màn hình nào.

### A15. Dialog host key (mới round 8)

| File | Nội dung |
|---|---|
| `ui/hostkey/HostKeyPrompt.kt` ✅ | `HostKeyPromptState` bridge suspend ↔ dialog. `UnknownHostDialog` (lần đầu) + `ChangedHostKeyDialog` (khóa đổi). 8 string VI/EN |
| `NeoApp.kt` ✅ | Thay `AutoTrustPrompts` bằng `DialogPrompts` — hết lỗ hổng C14 |

### A16. Màn hình SFTP (mới round 9)

| File | Nội dung |
|---|---|
| `ui/sftp/SftpBrowserViewModel.kt` ✅ | Stack điều hướng, cache listing, 4 kiểu sắp xếp, chọn nhiều, xóa/đổi tên/tạo thư mục/chmod, upload/download qua stream |
| `ui/sftp/SftpBrowserScreen.kt` ✅ | Breadcrumb + danh sách phân biệt thư mục/tệp/symlink, menu overflow (sắp xếp, ẩn tệp, tạo thư mục), nút xóa nổi khi chọn, dialog tạo thư mục + xác nhận xóa + xem chi tiết/chmod/copy path. **16 string VI/EN** |
| `ui/NeoApp.kt` ✅ | Route `sftp/{id}` — mở session riêng, không giành channel với terminal |
| `ui/TerminalScreen.kt` ✅ | Nút **SFTP** trên thanh trên |

**Đã chứng minh trên emulator:**
- Liệt kê file thật: `out/`, `cp.txt`, `run_test_sshd.ps1`, `RunTestSshd.java`
- Điều hướng vào `out/` → hiện 2 file `.class`
- Menu sắp xếp tiếng Việt
- **Tạo thư mục `neo_test_dir`** → xác nhận tồn tại trên đĩa Windows

**Chưa có:** xem trước file, transfer center (hàng đợi nhiều file), huỷ giữa chừng.

### A17. Upload/download qua SAF (mới round 11)

| File | Nội dung |
|---|---|
| `sftp/SftpTransferBridge.kt` ✅ | Chuyển `content://` Uri ↔ stream cho `SftpClient`. Đọc tên/kích thước từ `OpenableColumns`, đoán MIME theo đuôi, mode `"wt"` để truncate khi ghi đè |
| `ui/sftp/SftpTransferHost.kt` ✅ | Hai SAF launcher: `OpenDocument` cho upload, `CreateDocument` cho download. Thanh tiến trình + banner trạng thái |
| `ui/NeoApp.kt` ✅ | Nối `SftpTransferHost` bao quanh browser, banner kết quả 3 giây |

**Đã chứng minh trên emulator:**
- Upload: chọn ảnh từ SAF → `u3.png` 203.957 B lên server, xác nhận trên đĩa Windows
- Download: menu tệp → "Tài xuống" → SAF Save với tên điền sẵn → `/sdcard/Download/u3.png` 203.957 B

### E2d. Bug thật do round 11 tìm ra

| # | Bug | Triệu chứng | Nguyên nhân | Sửa |
|---|---|---|---|---|
| B14 | Nút "Tài xuống" không phản hồi | Dialog đứng im khi bấm | Trong `AlertDialog`, `confirmButton` chỉ render khi không phải directory — nhưng `dismissButton` set `actionDialog = CopyPath`, tạo dialog lồng nhau | Tách hai nút độc lập: `confirmButton` = tải xuống/chmod, `dismissButton` = copy path |
| B15 | Chỉ báo sai toạ độ khi test | Tap (730, 1141) không trúng nút ở (733, 1373) | Toạ độ ảnh chụp khác toạ độ input — status bar và navigation bar làm lệch | Dùng `uiautomator dump` lấy `bounds` thật thay vì ước lượng từ ảnh |

### E2e. Bug thật do round 12 tìm ra

| # | Bug | Triệu chứng | Nguyên nhân | Sửa |
|---|---|---|---|---|
| B16 | **Key bar bị bàn phím che** | Mở bàn phím → bar biến mất, terminal không dùng được | `Scaffold` không có `imePadding()`; keyboard vẽ đè lên nội dung | Thêm `.imePadding()` vào `Column` gốc |
| B17 | **Key bar ở trên ô nhập** | Bar nằm giữa terminal và ô nhập, cách bàn phím một khoảng | Thứ tự `Column`: view → bar → ô nhập. Muốn bar sát bàn phím thì phải đảo | **Chưa sửa** — hiện bar vẫn ở trên ô nhập. Đúng ra: view → ô nhập → bar |
| B18 | `ProcessShellFactory` không xuất output trên Windows | Terminal trắng hoàn toàn, không banner | `cmd.exe` chỉ ghi stdout khi sở hữu console; qua pipe thì im lặng | **Giới hạn của công cụ test**, không phải app. Đã quay lại `InteractiveProcessShellFactory` |

**Lưu ý về B17:** đó là lỗi bố cục thật, không phải thẩm mỹ. Một ngón tay cầm máy
thì bar phải sát ngón cái, tức là sát bàn phím — hiện phải vươn qua ô nhập.

### A18. Key bar (mới round 12)

| File | Nội dung |
|---|---|
| `terminal/input/TerminalKeyBar.kt` ✅ | `BarKey` (Ctrl/Alt/Shift/Special/Literal/ControlByte/Spacer), `ModifierState` sticky, `press()` mã hoá byte, `encodeLayout`/`decodeLayout` cho layout tuỳ chỉnh |
| `ui/terminal/TerminalKeyBarView.kt` ✅ | 3 hàng cuộn ngang, nút sáng khi modifier đang giữ. Không biết gì về SSH — chỉ trả byte |
| `ui/TerminalScreen.kt` ✅ | Gắn bar, `imePadding`, đọc `APPLICATION_CURSOR` từ emulator |

**Đã chứng minh trên emulator:**
- Bar hiện 3 hàng: `Ctrl Alt Esc Tab ↑↓←` / `PgUp PgDn Ins Del | ~ /` / `^D ^Z ^L ^A ^E ^K ^U ^W ^R`
- **Bấm ^C → session đóng** — đúng SIGINT, chứng minh byte `0x03` tới được server
- Bar nằm ngay trên bàn phím sau khi thêm `imePadding`

**Test:** 15 case — sticky modifier tiêu thụ đúng một lần, Ctrl-C = 1 byte, Alt thêm `ESC`,
Ctrl bị bỏ trên phím mũi tên, Shift+mũi tên dùng dạng CSI có tham số, round-trip layout.

### A13. Đổi tên & bản địa hoá (mới round 6)

| # | Việc | Chi tiết |
|---|---|---|
| 1 | Package → `com.tuissh.app` | 48 file Kotlin, 4 file test, mọi `import` |
| 2 | `applicationId` → `com.tuissh.app` | debug suffix `.debug` |
| 3 | `rootProject.name` → `TuiSSH` | APK tên `app-debug.apk` |
| 4 | Logo mới | `>_` trên nền `#101820`, chevron xanh `#3DDC97`, cursor trắng |
| 5 | `app_name` / title → "Tui SSH" | cả 2 ngôn ngữ |
| 6 | **Base `values/` = tiếng Việt** | 68 key viết tay, không máy dịch |
| 7 | `values-en/` = tiếng Anh | fallback cho máy English |
| 8 | `LocaleEngine` + `attachBaseContext` | **ép tiếng Việt mặc định** dù máy `en-US` |
| 9 | `NeoError.localized()` | lỗi hiện đúng ngôn ngữ thay vì message EN thô |

### A11. Terminal runtime + renderer (mới round 5)

| File | Nội dung |
|---|---|
| `terminal/session/TerminalSession.kt` ✅ | Pump 2 chiều SSH ↔ emulator. Read loop 16 KB buffer, write queue `Channel(256)`, resize đồng bộ 3 nơi (emulator/pty/renderer), `StateFlow` cho state/size/title, `SharedFlow` cho damage/bell/clipboard. Bracketed paste được tôn trọng. |
| `terminal/session/TerminalInputEncoder.kt` ✅ | `encodeChar` (Ctrl 0x00-0x1F + Ctrl+? = 0x7F, Alt+ESC prefix), `encodeKey` 26 phím (mũi tên có/không app-cursor mode, F1-F12 đúng xterm-256color, Shift+Tab = `ESC[Z`), SGR mouse + X10 mouse + focus event |
| `terminal/render/TerminalPalette.kt` ✅ | Resolve packed cell → `Color`, dựng 256-entry ANSI từ theme row, fallback mặc định |
| `terminal/render/NeoTerminalView.kt` ✅ | Compose `Canvas`: measure char width/line height bằng probe string, vẽ background/rule/selection/glyph/cursor, 3 kiểu con trỏ, selection theo ô |

**Chưa có:** scroll lịch sử bằng tay (scrollback đã có dữ liệu nhưng chưa hiển thị), bàn phím modifier bar, loupe, Kitty/Sixel render.

### A12. Data + UI + DI (mới round 5)

| File | Nội dung |
|---|---|
| `data/repo/ServerRepository.kt` ✅ | Một cửa duy nhất cho server/group/key/forward. Mã hoá password ở đây, không ở đâu khác. `saveServer(plain=null)` giữ nguyên secret, `""` xoá, còn lại mã hoá mới. |
| `ssh/SshSpecFactory.kt` ✅ | `ServerEntity` → `SshConnectSpec`, resolve password/key/passphrase từ vault, trả 3 nhánh `Ready`/`NeedsSecret`/`Failed` |
| `di/Graph.kt` ✅ | Container viết tay, không Hilt. Khởi tạo trong `NeoActivity.onCreate` |
| `ui/ServerScreens.kt` ✅ | `ServerListScreen` (search, favorite, delete) + `ServerEditScreen` (validate host/user/port) |
| `ui/TerminalScreen.kt` ✅ | Top bar hiện title OSC + kích thước lưới, `NeoTerminalView`, ô nhập lệnh |
| `ui/NeoApp.kt` ✅ | NavHost 3 route: `servers` → `edit/{id}` → `terminal/{id}` |
| `ui/server/ServerViewModels.kt` ✅ | `ServerListViewModel` (combine search), `ServerEditViewModel` (form + validate) |

### A10. AI — preset mở rộng

| Provider | Base URL | Protocol |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | openai_chat |
| ChatGPT (Codex) | `https://chatgpt.com/backend-api/codex` | codex |
| Anthropic | `https://api.anthropic.com` | anthropic_messages |
| DeepSeek | `https://api.deepseek.com/v1` | openai_chat |
| OpenRouter | `https://openrouter.ai/api/v1` | openai_chat |
| SiliconFlow | `https://api.siliconflow.cn/v1` | openai_chat |
| Zhipu GLM | `https://open.bigmodel.cn/api/paas/v4` | openai_chat |
| Zhipu GLM (Anthropic) | `https://open.bigmodel.cn/api/anthropic` | anthropic_messages |
| **DC AI** | `https://dc-ai.dabeecao.org/v1` | openai_chat |
| **DC AI (Anthropic)** | `https://dc-ai.dabeecao.org/v1` | anthropic_messages |
| **CHJBI** | `https://api.chjbi.net/v1` | openai_chat |

Spec DC AI đã bóc đầy đủ vào `spec/dabeecao_openapi.json` + `spec/dabeecao_docs.txt`:
- `GET /v1/models`, `GET /v1/usage`, `GET /v1/dashboard/billing/subscription`
- `POST /v1/chat/completions`, `/v1/responses`, `/v1/messages`
- `POST /v1beta/models/{model}:generateContent`, `:streamGenerateContent`
- `POST /v1/images/generations`, `/v1/images/edits`
- `GET /api/search`, `/api/fetch-webpage`, `/api/tts`, `/api/models/metadata`
- 4 kiểu auth: `Authorization: Bearer dc_...`, `x-api-key`, `x-goog-api-key`, `?key=`

---

## PHẦN B — CHƯA XONG (đã định hướng, chưa có code)

### B1. SSH runtime ⏳

| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| `SshSession` (kết nối, auth, channel) | ⏳ | Dùng `com.jcraft.jsch` (mwiede 0.2.20) — đã khai báo trong gradle |
| Host key verify + first-connect prompt | ⏳ | `KnownHostEntity` đã có, logic chưa nối |
| SOCKS5 proxy | ⏳ | JSch `setProxy(new ProxySOCKS5(...))` |
| ProxyJump / bastion chain | ⏳ | JSch `setProxy(new ProxySSH(...))`, cần chống chu trình (`NeoError.CyclicBastion` đã có) |
| Port forwarding runtime | ⏳ | JSch `setPortForwardingL/R`, dynamic cần tự viết SOCKS server |
| Keyboard-interactive auth | ⏳ | JSch `UserInfo` |
| Agent forwarding | ⏳ | JSch `setAgentForwarding(true)` |
| Reconnect + keepalive | ⏳ | |

### B2. SFTP ⏳
- `SftpClient` (list/stat/read/write/mkdir/rm/rename/chmod/symlink)
- `SftpTransferCenter` (queue, progress, cancel — có `CancelFlag` trong bản gốc)
- Thumbnail loader cho ảnh/video
- Batch rename

### B3. Terminal runtime ⏳
- `NeoTerminalView` Compose render (Canvas, glyph cache, damage rect)
- Xử lý bàn phím: modifier, Ctrl/Alt/Esc, `TerminalFixedKeySettings`
- Selection + copy (đã có `textRange()` ở buffer)
- Kitty graphics / Sixel renderer
- Loupe (kính lúp chọn chữ)
- ZMODEM

### B4. Mosh ❌
Chưa có dòng nào. Cần: `MoshPacketCodec`, `MoshFragmenter`, `MoshCryptoOCB`, `MoshTransport (UDP)`, `MoshBootstrap (parse "MOSH CONNECT")`, `MoshClientSession`, `MoshPredictionEngine`, `MoshConditionalOverlay`.

### B5. Docker ⏳
Parser cho `docker ps/images/volumes/networks/stats/history/compose ls/events`.
Lệnh đã bóc đầy đủ ở `agent.md` mục 6.3.
Chưa có: `DockerRunCommandParser` (parse `docker run`/`podman run` thành form).

### B6. Monitor ⏳
Parser CPU (`/proc/stat`), mem (`/proc/meminfo`), load, uptime, df, net, process list (`top -b`), `nvidia-smi -q -x`, sensors.
Regex đã bóc hết ở `agent.md` mục 6.4.
Chưa có: `LinuxParser`, `BsdOSParser`, `MacOSParser`, `WinOSParser`, `FreeBSDParser`.

### B7. AI runtime ⏳🟡
✅ Có: entity provider (8 preset), model, conversation, message, skill, browser tab.
⏳ Chưa: HTTP client (`OkHttp`), SSE streaming, tool-call loop, `OpenAIOAuthClient` (loopback :1455), `LoopbackCallbackServer`, prompt builder, destructive-command guard, skill loader.

### B8. UI ⏳
Toàn bộ 12 màn hình: server list, server edit, terminal, SFTP browser, Docker (5 tab), monitor, scripts, AI chat, settings, keys, theme editor, onboarding.

### B9. Android-only plumbing ⏳
- `WorkManager` cho monitor nền (thay `BGTaskScheduler`)
- `ForegroundService` giữ SSH khi background
- `BiometricPrompt` (thay FaceID app lock)
- `NsdManager` (thay Bonjour)
- `Wear`/widget `Glance` (thay iOS widget)
- `ACTION_SEND` share extension (thay Share Extension)
- `strings.xml` cho 12 ngôn ngữ

---

## PHẦN C — CHƯA GIẢI MÃ / CHƯA BÓC ĐƯỢC

| # | Hạng mục | Lý do | Đã bóc được gì |
|---|---|---|---|
| C1 🔒 | **SF Symbols** (`Symbols.swift`, `sfsymbol5.txt`, `sfsymbol6.txt`) | Danh sách icon iOS, không dùng được trên Android | Chỉ có tên file |
| C2 🔒 | **`Assets.car`** (3.4 MB) | Compiled asset catalog, cần macOS `assetutil` | REA báo `asset_catalog` cần macOS. **Chưa bóc** |
| C3 🔒 | **`default.metallib`** (23.7 KB) | Metal shader đã compile cho GPU Apple | **Chưa bóc** — không dùng được trên Android |
| C4 🔒 | **Nội dung `markdown` renderer** | `MarkdownUI`/`MarkdownCore` là thư viện bên thứ ba | Chỉ có tên class |
| C5 🔒 | **Settings bundle đầy đủ** | `Settings.bundle` là XML | Mới bóc `Info.plist`; các `.plist` còn lại (23 file) **chưa đọc** |
| C6 🔒 | **Nội dung `PushNotificationComponents`** | Phụ thuộc FCM/APNs | Chỉ có tên class |
| C7 🔒 | **App Intents / Siri shortcuts** | Metadata trong `Metadata.appintents/` | `version.json` = 51 byte, chưa parse |
| C8 🔒 | **NSString literals bị nén** | Một số chuỗi nằm trong `__cstring` đã dedup | Bóc được 121.370 chuỗi ASCII nhưng **không chắc đủ 100%** |
| C9 🔒 | **Toàn bộ Swift metatype/field name** | Reflection metadata | Bóc được 14.921 identifier dạng camelCase trong `spec/field_names.txt`, lẫn nhiều rác |
| C10 🔒 | **Wire format Mosh** | Không có symbol | Suy ra từ chuẩn Mosh công khai, chưa đối chiếu binary |
| C11 🔒 | **OAuth client_id OpenAI** | Bị obfuscate | Chỉ thấy endpoint `auth.openai.com/oauth/authorize`, `localhost:1455/auth/callback` |
| C12 🔴 | **Raw host key blob** | JSch `Session.hostKey` chỉ trả algorithm + key bytes dạng `byte[]` với một số thuật toán; wrapper hiện chưa lấy được blob đủ tin cậy | Fingerprint hiện dựa trên **SHA256(host:port + label)** — phát hiện được **đổi thuật toán**, KHÔNG phát hiện được đổi key cùng thuật toán. Đã ghi rõ trong docstring `HostKeyVerifier.fingerprintForLabel`. Cần `ServerHostKeyVerifier` hoặc đọc `session.hostKey.key` để đóng lỗ hổng này |
| C13 🔒 | **`bcrypt_pbkdf`** | Android platform không có bcrypt; chưa viết lại Blowfish | `BcryptPbkdf.isAvailable = false`; passphrase OpenSSH key **không xác minh cục bộ**, để lần kết nối quyết định |
| C14 ✅ | ~~Auto-trust host key~~ | **Đã đóng round 8** | `DialogPrompts` + `UnknownHostDialog` + `ChangedHostKeyDialog` — không còn tự động tin |
| C15 🔴 | **Terminal render trên emulator chưa hoàn hảo** | Chữ nằm dọc giữa màn hình, cắt mép trái, session đóng khi Enter | Đã sửa 3 lỗi (text-first-then-Enter, clip theo grid, resize guard) nhưng **chưa chụp lại ảnh xác nhận** |

**Đánh giá:** không có đoạn mã nào bị **chặn bởi FairPlay**. `cryptid=0` nghĩa là binary đã giải mã. "Chưa giải mã" ở đây là **chưa bóc hết nội dung**, không phải bị DRM.

---

## PHẦN D — CHƯA DỊCH / BẢN ĐỊA HOÁ

| # | Hạng mục | Tình trạng |
|---|---|---|
| D1 | `locales_vi.json` (3590 key của app gốc) | ✅ đã bóc, sẵn sàng import |
| D2 | **Base `values/strings.xml` = tiếng Việt** | ✅ **68 key viết tay** — nav, list, form, terminal, 17 lỗi, AI, common |
| D3 | `values-en/strings.xml` | ✅ 68 key tiếng Anh, khoá khớp 1:1 |
| D4 | **Tiếng Việt là mặc định** | ✅ `LocaleEngine` + `attachBaseContext`, đã chụp ảnh xác nhận |
| D5 | `NeoError.localized()` | ✅ lỗi hiện đúng ngôn ngữ |
| D6 | 3590 key gốc → Android resource | ⏳ chưa convert. Cần script map `%1$@` → `%1$s`, `%lld` → `%1$d` |
| D7 | 10 ngôn ngữ còn lại | ⏳ chưa convert (zh/ja/ko/ru/de/fr/es/pt-BR/tr) |
| D8 | Chuỗi viết thẳng trong Swift (`__cstring`) | 🔒 chưa trích hết |
| D9 | `skill-creator.md` | 🔒 giữ EN (là spec cho AI, không phải UI) |
| D10 | `agent.md` / `plan.md` / `check.md` | ✅ **tiếng Việt** |

---

## PHẦN E — KIỂM CHỨNG

| Kiểm tra | Kết quả | Chi tiết |
|---|---|---|
| `gradle wrapper` | ✅ | Gradle 8.9, JDK 21.0.10 |
| Gradle sync | ✅ | WorkManager 2.9.1, MINA SSHD 2.15.0 |
| **`assembleDebug`** | ✅ **BUILD SUCCESSFUL** | 2026-11-06 01:49 |
| **`testDebugUnitTest`** | ✅ **50/50 pass** | 4 suite, gồm 7 test SSH thật |
| **Cài lên emulator** | ✅ | Pixel 6 API 34, Android 14 |
| **App chạy, không crash** | ✅ | `_shots/01_launch.png` |
| **Luồng thêm server** | ✅ | `03_filled.png` → `04_list.png`, Room ghi WAL 197 KB |
| **Mở terminal** | ✅ | `05_terminal.png` — hiện "Authentication failed" (đúng: 127.0.0.1:22 không có sshd) |
| **SSH kết nối thật** | ✅ | 7/7 integration test với MINA SSHD |
| **Tên + logo mới** | ✅ | `_shots/11_tuissh.png` |
| **Tiếng Việt mặc định** | ✅ | `_shots/15_vi_default.png` — máy `en-US`, app hiện VI |
| Render `NeoTerminalView` | ⏳ **chưa nhìn thấy** | Chưa kết nối được server thật nên terminal chưa vẽ frame nào |
| Instrumented test | ⏳ chưa viết | |

**Đọc bảng này cho đúng:** app **đã chạy**, SSH **đã kết nối được** (trong test),
tiếng Việt **đã là mặc định**. Còn thiếu đúng một mảnh: **chưa ai nhìn thấy
terminal render dữ liệu thật**, vì cần một SSH server thật ngoài test.

### E2b. Bug thật do round 6 tìm ra

| # | Bug | Triệu chứng | Nguyên nhân | Sửa |
|---|---|---|---|---|
| B5 | **SSH không kết nối được cái nào** | `Err(Io(detail=reject HostKey: ...))` mọi lần | `RejectingHostKeyRepository.check()` trả `NOT_INCLUDED` → JSch hủy **trước khi** tới bước verify | Đổi sang `CapturingHostKeyRepository`: `check()` trả `OK`, ghi lại key, rồi verify **sau** `connect()` và disconnect nếu user từ chối |
| B6 | **`openShell` fail** | `failed to send channel request` | `UnknownCommandFactory` không cung cấp shell | `InteractiveProcessShellFactory` + `ProcessShellCommandFactory` |
| B7 | **Unit test JVM crash** | `Method d in android.util.Log not mocked` | `NeoLog` gọi `Log` trực tiếp | `runCatching` quanh mọi lời gọi `Log` + `isReturnDefaultValues` |
| B8 | **Tiếng Việt không mặc định** | máy `en-US` → app hiện English | Android ưu tiên `values-en` hơn `values` theo locale máy | Base `values/` = VI + `localeConfig` + `LocaleEngine` ép tag trong `attachBaseContext` |
| B9 | **Đổi package làm rơi test** | `Redeclaration` ×4 | `Move-Item` tạo `com/tuissh/neoserver/` thay vì `com/tuissh/app/` | Dọn dir cũ, tạo lại test dưới `com/tuissh/app/` |

### Lỗi build đã gặp và đã sửa

| # | Lỗi | Nguyên nhân | Sửa |
|---|---|---|---|
| 1 | JDK 25 không chạy được Gradle 8.9 | Android Studio JBR = JDK 25.0.4 | Dùng `C:\Program Files\Java\jdk-21.0.10` |
| 2 | `mainManifest` không tồn tại | chưa viết `AndroidManifest.xml` | đã tạo + permission đầy đủ |
| 3 | `mipmap/ic_launcher` not found | chưa có icon | adaptive icon vector 2 tầng |
| 4 | `Unresolved reference 'work'` ×5 | thiếu dependency WorkManager | thêm `androidx.work:work-runtime-ktx:2.9.1` |
| 5 | `Only one companion object is allowed` | khai báo 2 companion trong `NeoApplication` | gộp thành một |
| 6 | `Unresolved reference 'letter'` | dùng `e.letter` thay `e.level.letter` | sửa `NeoLog` |
| 7 | `ByteArray != String` | `readString()` trả `ByteArray?` cho cipher/kdf name | `.toString(Charsets.US_ASCII)` |
| 8 | `Unresolved reference 'PpKConverter'` | class chưa tồn tại | đã viết `PpKConverter.kt` |
| 9 | `Unresolved reference 'BcryptPbkdf'` | class chưa tồn tại | đã viết `BcryptPbkdf.kt` |
| 10 | `Unresolved reference 'PassphraseVerdict'` | enum nằm trong `AesCtrCipher` | qualify đầy đủ |
| 11 | `Unresolved reference 'notify'` | `getSystemService` trả `Any?` | dùng `NotificationManager::class.java` |
| 12 | `HostKeyRepository` không implement đủ | mwiede JSch 0.2.20 dùng `getHostKey(String, String)` + `remove(String, String, byte[])`, không phải biến thể `byte[] type` | `javap` interface rồi viết đúng 7 method |
| 13 | `InputStream` ≠ `OutputStream` ở `ShellHandle` | JSch đặt tên theo góc nhìn **channel**: `getInputStream()` = server→ta, `getOutputStream()` = ta→server | đảo lại: `input = channel.outputStream` |
| 14 | `SshError` không dùng được trong `NeoResult.Err` | `NeoResult.Err` nhận `NeoError`, `SshError` kế thừa `Exception` | dùng `NeoError` trực tiếp ở call site |

### Thay đổi so với kế hoạch ban đầu

| # | Thay đổi | Lý do |
|---|---|---|
| S1 | `BcryptPbkdf.isAvailable = false` | Android không có bcrypt primitive. Passphrase OpenSSH key được coi là **không xác minh được cục bộ**, để lần kết nối quyết định, thay vì báo sai là "sai mật khẩu" |
| S2 | `PpKConverter` trả `Failure` kèm hướng dẫn | Không thể convert PPK v2/v3 đúng nếu thiếu Argon2; thà báo rõ còn hơn tạo PEM hỏng |
| S3 | Thêm `values-vi/strings.xml` + `NeoLocale` | He yêu cầu tiếng Việt là first-class |
| S4 | Thêm preset `dcai`, `dcai-anthropic`, `chjbi` | He cung cấp 2 API |
| S5 | `PROTOCOL_GEMINI` thêm vào enum | DC AI có route `/v1beta` |
| S6 | `HostKeyVerifier` dùng fingerprint từ **label** thay vì blob | JSch không đưa blob ra đủ tin cậy. Đây là **giảm bảo mật có ý thức**, đã ghi thành C12 chứ không giấu |
| S7 | `WIDE_TAIL` chuyển từ cờ sang packing kind | Cờ thứ 9 không vừa trong nibble 4 bit; test bắt được ngay |

### E2. Bug thật do unit test bắt được (round 4)

Bốn lỗi dưới đây **đã tồn tại trong code**, không phải lỗi test. Test tìm ra chúng là
lý do unit test đáng giá hơn một buổi đọc lại code.

| # | Bug | Triệu chứng | Nguyên nhân thật | Sửa |
|---|---|---|---|---|
| B1 | **`ESC [` giết toàn bộ CSI** | `\e[2J` không xoá màn hình, thay vào đó chữ `[2J` lọt ra màn hình như text | `escape()` xét `b in 0x30..0x7E` **trước** nhánh `b == '['`. `'['` = 0x5B nằm trong khoảng đó, nên `[` bị coi là final byte thay vì mở CSI. Cùng lỗi với `]`, `P`, `X`, `^`, `_` — tức OSC/DCS cũng chết | Đưa 6 nhánh introducer **lên trước** kiểm tra `0x30..0x7E` |
| B2 | **Wide-char tail không bị xoá** | `日 本` dư dấu cách; nửa phải của ký tự rộng sống sót sau `EL`/`ED` | Cờ `FLAG_WIDE_TAIL = 1 shl 8` không pack được: `flagsOf()` chỉ lấy **4 bit** (`ushr 28 and 0xF`), toàn bộ bit 8 bị cắt. `isWideTail()` luôn false | Bỏ hẳn cờ, dùng **packing kind riêng** `KIND_WIDE_TAIL = 3` cho ô tail |
| B3 | **`EL`/`ED`/`TBC` xoá sai nửa dòng** | `\e[K` xoá từ đầu dòng thay vì tới cuối dòng | Hàm `p(i)` có default = **1** cho mọi tham số CSI. Nhưng `ED`, `EL`, `TBC` mặc định phải là **0** | `p(0, def = 0)` cho `J`, `K`, `g` |
| B4 | **`erase` cắt đôi wide glyph** | Xoá từ ô tail để lại nửa ký tự mồ côi | `erase()` chỉ xoá đúng khoảng yêu cầu, không nới ra để phủ cả ký tự rộng | Thêm `eraseRange()` tự nới biên; `erase()` cũng lùi 1 nếu bắt đầu ở tail |

Ngoài ra `NeoFormat.bytes()` tính Sai: `1000` với base DECIMAL trả `"1000 B"` thay vì
`"1.0 KB"` vì ngưỡng so sánh hard-code `1024`.

### E2c. Bug thật do round 8 tìm ra

| # | Bug | Triệu chứng | Nguyên nhân | Sửa |
|---|---|---|---|---|
| B10 | **`removeRecursive` đệ quy vô hạn** | Test suite **treo 10+ phút**, không ra kết quả | `list()` trả cả `.` và `..`; entry `..` là directory nên gọi đệ quy vào `..` → lặp mãi | `list()` lọc bỏ `.`/`..`; `removeRecursive` thêm `depth` + `MAX_DEPTH = 64` làm chốt thứ hai |
| B11 | **`canonical("~")` trả path có dấu `:`** | Mọi thao tác SFTP lỗi `Invalid filename` | `pwd()` trên server dùng chung filesystem host trả `E:/Code/...`; dấu `:` không hợp lệ trong tên file SFTP | `~` → `.` (thư mục làm việc của channel), không ghép `pwd()` |
| B12 | **`mkdirs` nuốt mọi lỗi** | `mkdirs` trả Ok nhưng thư mục không tồn tại → `list` sau đó `NO_SUCH_FILE` | `runCatching { sftp.mkdir(...) }` bỏ qua cả lỗi thật | Chỉ bỏ qua mã 4 (đã tồn tại), lỗi khác trả `SftpFailure` kèm path |
| B13 | **`mkdirs` ghép `pwd()` vào path** | Cùng lỗi `Invalid filename` | Ghép path tuyệt đối từ `pwd()` (có dấu `:`) | Giữ path **tương đối** với CWD của channel |

**Bài học từ round 8:** mình mò 40 phút vì chạy cả suite rồi đoán. Lẽ ra phải
**đọc code `removeRecursive` ngay** khi thấy treo — chỗ đệ quy luôn là nghi phạm đầu.

### E3. Test chưa đủ chỗ

Những thứ **chưa có test**:

- `KeyParser` — 7 định dạng key, chưa test cái nào
- `TerminalSession` — chưa test pump loop, chưa test mất kết nối
- `ProxyChainResolver` — chưa test vòng lặp bastion
- `SftpClient` — chưa test symlink, chưa test permission denied thật
- `VtParser` — test gián tiếp qua emulator, chưa test UTF-8 cắt giữa 2 lần `feed()`
- `VtEmulator` — chưa test SGR màu (`30-37`, `38;5`, `38;2`), `REP`, `CHA`, `VPA`

---

## VIỆC TIẾP THEO (ưu tiên)

| # | Việc | Vì sao |
|---|---|---|
| 1 | **Lưu lịch sử hội thoại AI** | Đóng app là mất; `AiConversationEntity` đã có trong DB |
| 2 | **Dialog duyệt `run_command` trên emulator** | Backend đã chứng minh (có/không duyệt); cần thấy UI thật |
| 3 | Transfer center | Hàng đợi upload/download, huỷ giữa chừng |
| 4 | Mosh | Cuối |
| — | **Redesign** | He tự làm |

## CHỨC NĂNG ĐÃ HOÀN CHỈNH

| Tính năng | Test | Chạy thật |
|---|---:|---|
| SSH connect + auth (password + **key**) + host key | 38 | ✅ emulator + VPS |
| Terminal emulator (VT) | 50 | ✅ emulator |
| Key bar (Ctrl/Alt/Esc/arrow) | 15 | ✅ emulator |
| Scrollback + cử chỉ cuộn | 10 | ✅ (test ánh xạ) |
| SFTP đầy đủ + upload/download | 14 | ✅ emulator |
| AI: chat + tool card + streaming | 7 + 9 + 28 + 5 | ✅ emulator + API thật |
| **AI tool execution (6 tool)** | **6** | ✅ **VPS Linux thật** |
| **Monitor (parser + client + UI)** | **52** | ✅ **VPS Linux thật** |
| **Docker (parser + client + UI)** | **22** | ✅ **VPS Linux thật** |
| Credential cache | 17 | ✅ (test) |
| KeyParser 7 định dạng | 19 | ✅ (test) |
| Bản địa hoá VI/EN | — | ✅ emulator |

**Tổng: 296 test, 0 fail.**

## CÁCH CHẠY TEST VỚI HẠ TẦNG THẬT

```powershell
# VPS Linux (Monitor + Docker + AI tool)
$env:LIVE_HOST='<ip>'
$env:LIVE_USER='ubuntu'
$env:LIVE_KEY_PATH='<path to private key>'

# API AI
$env:AI_TEST_KEY='capi-...'

.\gradlew.bat testDebugUnitTest --no-daemon
```

Không có biến môi trường thì các test đó **skip**, build offline vẫn sạch.
Không có credential nào nằm trong repo — đã grep xác nhận.

## LỖI CỦA MÌNH QUA CÁC ROUND

Đáng ghi lại vì lặp lại nhiều lần:

| # | Lỗi | Round |
|---|---|---|
| 1 | Tap sai toạ độ vì lấy pixel từ ảnh thay vì `uiautomator dump` | 11, 13 |
| 2 | Chạy 3 agent Gradle song song → hỏng `kspCaches`, mất 40 phút | 16 |
| 3 | Fixture `/proc/stat` đặt `idle` sai cột | 17 |
| 4 | Fixture scrollback gọi `scrollUp` sau mỗi lần ghi | 22 |
| 5 | Test `docker_ps` tìm chữ "image" không có trong output | 23 |

**Mẫu chung:** viết test theo trực giác thay vì đọc code/dữ liệu trước. Mỗi lần đều mất
10-15 phút. Cách tránh: chạy lệnh thật, xem output thật, rồi mới viết assertion.