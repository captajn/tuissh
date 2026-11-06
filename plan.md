# PLAN.md — Lộ trình thi công

> Mục tiêu: port NeoServer iOS (Swift + Rust russh) → Android Kotlin, parity đầy đủ.
> Đơn vị: 1 giai đoạn = một mốc app chạy được, không phải mốc code xong.

---

## Nguyên tắc

1. **Mỗi giai đoạn phải ra app cài được.** Không gộp.
2. **Build sau mỗi 10 file.** 19 file hiện chưa compile lần nào.
3. **Parse trước, UI sau.** Parser dễ test bằng JVM unit test, UI thì không.
4. **Shell + regex là ROI cao nhất.** Docker/Monitor không cần crypto, không cần async phức tạp.
5. **Mosh làm cuối.** Nó là hố đen thời gian.

---

## GIAI ĐOẠN 0 — Baseline build (0.5 ngày)

| # | Việc | Done khi |
|---|---|---|
| 0.1 | `gradle wrapper --gradle-version 8.9` | có `gradlew.bat` |
| 0.2 | `assembleDebug` lần đầu | BUILD SUCCESSFUL hoặc danh sách lỗi có hệ thống |
| 0.3 | Sửa toàn bộ lỗi compile 19 file hiện có | `assembleDebug` pass |
| 0.4 | `AndroidManifest.xml` + `MainActivity` tối thiểu | cài được, mở lên thấy "NeoServer" |
| 0.5 | JVM unit test cho `VtParser` + `KeyParser` + `NeoFormat` | `testDebugUnitTest` pass |

**Rủi ro:** lỗi import Compose/Room KSP. Sửa xong là hết chặn.

---

## GIAI ĐOẠN 1 — SSH client thật (3–4 ngày)

Mốc: **mở được terminal tới server, gõ lệnh, thấy output.**

| # | Việc | Phụ thuộc | File dự kiến |
|---|---|---|---|
| 1.1 | `SshHostKeyVerifier` — so khớp `KnownHostEntity`, sinh `NeoError.HostKeyChanged` | A2 | `ssh/hostkey/HostKeyVerifier.kt` |
| 1.2 | `SshSession` — connect/auth(channel open) bằng JSch | 1.1 | `ssh/SshSession.kt` |
| 1.3 | `SshAuthResolver` — password / key / keyboard-interactive / ask | 1.2 + `KeyParser` | `ssh/SshAuthResolver.kt` |
| 1.4 | `SshProxyChain` — SOCKS5 + ProxyJump, chống chu trình | 1.2 | `ssh/SshProxyChain.kt` |
| 1.5 | `TerminalSession` — nối `VtEmulator` ↔ SSH channel, resize, keepalive | 1.2 | `terminal/TerminalSession.kt` |
| 1.6 | `NeoTerminalView` — Compose `Canvas` render buffer, damage repaint | 1.5 | `terminal/ui/NeoTerminalView.kt` |
| 1.7 | `TerminalInputEncoder` — phím thường, Ctrl/Alt/Esc, arrow app-mode, F1-F12 | 1.6 | `terminal/input/TerminalInputEncoder.kt` |
| 1.8 | Màn `ServerListScreen` + `ServerEditScreen` | A2 | `ui/server/*.kt` |
| 1.9 | `MainActivity` nav graph | 1.8 | `ui/NeoApp.kt` |

**Done:** cài app → thêm server → tap → thấy prompt bash → gõ `ls` → thấy kết quả → resize xoay máy không vỡ layout.

---

## GIAI ĐOẠN 2 — SFTP + quản lý file (2–3 ngày)

Mốc: **duyệt file server, upload/download, sửa quyền.**

| # | Việc |
|---|---|
| 2.1 | `SftpClient` — list/stat/read/write/mkdir/remove/rename/chmod/symlink/readlink |
| 2.2 | `SftpTransferCenter` — queue, progress flow, cancel flag, retry |
| 2.3 | `SftpBrowserScreen` — list 2 cột, breadcrumb, sort, search |
| 2.4 | Transfer sheet + notification tiến độ |
| 2.5 | Editor mở file remote (`EditorLib` port) — text + hex |
| 2.6 | Batch rename + custom menu (`SftpCustomMenuEntity`) |

**Done:** upload file 100 MB, thấy tiến độ, cancel giữa chừng không treo; sửa `chmod 755` được.

---

## GIAI ĐOẠN 3 — Docker + Monitor (3–4 ngày)

Mốc: **xem container, xem CPU/RAM/disk, chart chạy.**

| # | Việc | Ghi chú |
|---|---|---|
| 3.1 | `DockerClient` — chạy lệnh qua SSH exec, parse JSON | Lệnh ở `agent.md` §6.3 |
| 3.2 | `DockerRunCommandParser` — `docker run` → form | Có `NeoError.UnsupportedRunCommand` sẵn |
| 3.3 | `ComposeParser` — `compose ls --format json` | |
| 3.4 | `DockerScreen` — 4 tab: Containers / Images / Volumes / Networks | |
| 3.5 | `ContainerDetail` — logs stream, stats, inspect, exec terminal | |
| 3.6 | `OsDetector` — `cat /etc/os-release`, `uname -a` | 12 distro string đã bóc |
| 3.7 | `LinuxMetricsParser` — cpu/mem/load/uptime/df/net | Regex ở `agent.md` §6.4 |
| 3.8 | `MacMetricsParser` + `BsdMetricsParser` + `FreeBSDMetricsParser` | |
| 3.9 | `TopParser` — process list | các nhánh `skip parse top ...` |
| 3.10 | `GpuParser` — `nvidia-smi -q -x` | |
| 3.11 | `MonitorViewModel` + chart (Compose Canvas) | |
| 3.12 | `WorkManager` poll nền + alert threshold | thay `BGTaskScheduler` |

**Done:** server Ubuntu có Docker → thấy 5 container → tap 1 cái thấy log realtime; xem được CPU 24h.

---

## GIAI ĐOẠN 4 — Terminal nâng cao + Multiplexing (2–3 ngày)

| # | Việc |
|---|---|
| 4.1 | Selection + copy/paste (dùng `TerminalBuffer.textRange`) |
| 4.2 | Loupe magnifier |
| 4.3 | Bàn phím phụ: modifier bar, elm key bar, `KeyboardSymbolGroup*` |
| 4.4 | Theme import: Ghostty / Warp `.yaml` / iTerm2 `.itermcolors` |
| 4.5 | Highlight rules |
| 4.6 | Tabs + pane split (Side by Side / Stacked) |
| 4.7 | Kitty graphics + Sixel render |
| 4.8 | ZMODEM (sz/rz) |
| 4.9 | Speech-to-text cho terminal (`TerminalSpeechRecognizer`) |
| 4.10 | Tập lệnh đã lưu, chạy trên nhiều server |

---

## GIAI ĐOẠN 5 — AI agent (4–5 ngày)

Mốc: **chat, AI chạy lệnh thật trên server, có confirm.**

| # | Việc | Ghi chú |
|---|---|---|
| 5.1 | `AiHttpClient` (OkHttp) — 3 protocol: openai_chat / openai_responses / anthropic_messages | |
| 5.2 | SSE streaming parser | |
| 5.3 | `AiToolRegistry` — 13 tool theo schema §6.7 | |
| 5.4 | `AiToolExecutor` — execute/read_file/write_file/list_dir/upload/download | |
| 5.5 | `DestructiveCommandGuard` — 11 regex §6.6 | |
| 5.6 | `AiPlanMode` — chặn mutate khi bật | |
| 5.7 | `AiSkillLoader` — `SKILL.md` → `AiSkillEntity` (frontmatter) | |
| 5.8 | `OpenAIOAuthClient` + `LoopbackCallbackServer` :1455 | |
| 5.9 | `AiBrowserSession` — WebView + `browser-snapshot.js` | |
| 5.10 | `AiChatScreen` — message list, markdown render, tool card, confirm dialog | |
| 5.11 | Provider editor + model refresh + usage tokens | |

**Done:** "kiểm tra disk server", AI gọi `execute` với `df -h`, hiện confirm, chạy, tóm tắt kết quả.

---

## GIAI ĐOẠN 6 — Mosh (5–8 ngày) ⚠️ rủi ro cao

| # | Việc | Khó |
|---|---|---|
| 6.1 | `MoshWire` — varint, fragment header | vừa |
| 6.2 | `MoshPacketCodec` — serialize/deserialize hướng | vừa |
| 6.3 | `MoshCryptoOCB` — OCB-AES-128 | **khó** — phải tự viết, Android không có OCB |
| 6.4 | `MoshFragmenter` — chia/gộp theo MTU | vừa |
| 6.5 | `MoshTransport` — `DatagramSocket` + ack/timeout | vừa |
| 6.6 | `MoshBootstrap` — parse `MOSH CONNECT <port> <key>` qua SSH | dễ |
| 6.7 | `MoshClientSession` — SST + diff state | **khó** |
| 6.8 | `MoshPredictionEngine` — echo local | **khó** |
| 6.9 | `MoshConditionalOverlay` — hiển thị prediction mờ | vừa |

**Quyết định cần chốt:** nếu bỏ Mosh, giai đoạn này thành 0 ngày và tiết kiệm ~1/4 tổng dự án.

---

## GIAI ĐOẠN 7 — Hoàn thiện (3–4 ngày)

| # | Việc |
|---|---|
| 7.1 | `strings.xml` cho 12 ngôn ngữ từ `locales_*.json` |
| 7.2 | Onboarding |
| 7.3 | App lock (`BiometricPrompt`) |
| 7.4 | Import/export JSON backup (server/key/script/proxy) |
| 7.5 | `NsdManager` Bonjour discovery |
| 7.6 | Wake-on-LAN |
| 7.7 | Widget Glance + tile Quick Settings |
| 7.8 | Notification channel + ongoing terminal service |
| 7.9 | Crash handler + log export |
| 7.10 | ProGuard + release build + ký |

---

## Tổng thời lượng ước tính

| Kịch bản | Thời gian |
|---|---|
| Có Mosh, đầy đủ | **~25–35 ngày** làm liên tục |
| Không Mosh | **~18–25 ngày** |
| Chỉ SSH+SFTP+Docker+Monitor+AI | **~14–18 ngày** |

---

## Thứ tự ưu tiên nếu bị cắt

```
P0 (không có = vô dụng):  GĐ0 → GĐ1 → GĐ2
P1 (giá trị cao):         GĐ3 → GĐ5
P2 (khác biệt hóa):       GĐ4
P3 (nice to have):        GĐ6
P4 (để ship):             GĐ7
```

---

## Chốt quyết định cần He trả lời

| # | Câu hỏi | Ảnh hưởng |
|---|---|---|
| Q1 | Mosh có cần không? | ±8 ngày |
| Q2 | SSH engine: JSch (đang chọn) hay bind russh qua NDK? | ±5 ngày, JSch mất post-quantum KEX |
| Q3 | Sync: bỏ, Google Drive, hay WebDAV? | ±2 ngày |
| Q4 | Có cần port sang iOS-style UI hay Material 3 thuần? | ±3 ngày |
| Q5 | Cần Google Play Billing hay bỏ hẳn phần Pro? | ±3 ngày |
