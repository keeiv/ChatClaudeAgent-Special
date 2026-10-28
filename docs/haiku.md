# CCA Haiku（Android 專用特殊版本）

**在手機上輕鬆編程。** Haiku 是只在 Android 上使用的 CCA 特殊版本：專案存在手機裡，AI 直接修改檔案，按「預覽」立刻在手機上看到結果。它**不是**遠端操作電腦，不連線到你的 PC，也不需要電腦開著。版本號自成一列，從 0.0.1 起算。

## 能做什麼

- **網頁專案**：HTML、CSS、JavaScript。新專案附一個首頁、樣式表與腳本。
- **對話修改**：告訴 Haiku 想做什麼，它用四個工具處理專案：列出檔案、讀取、寫入、刪除。工具只能碰目前專案的資料夾。
- **預覽**：在第二個 WebView 開啟專案的 `index.html`，頁面裡的 JavaScript 照常執行；按「返回編輯」或系統返回鍵回到對話。
- **每一輪都能還原**：每一輪開始前先保存專案（保留最近 20 個還原點），回覆下方的「還原這一輪」會把檔案放回這一輪開始前的樣子，對話保留。
- **手動編輯**：「檔案」分頁可以打開任何檔案直接修改、新增或刪除。
- **匯出**：把專案打包成 ZIP 存到手機的「下載/CCA Haiku」。

## AI 與費用

Android 上沒有 Claude Code 與 Codex CLI，所以 Haiku 用 **API 金鑰**直接呼叫模型：

- 供應商：Claude（Anthropic API）或 GPT（OpenAI API）。API 依 Token 計費，和 Claude／ChatGPT 的訂閱分開。每一輪下方顯示這輪用了多少輸入與輸出 Token。
- 金鑰用 Android Keystore 的 AES-GCM 金鑰加密後保存，只有密文存在 App 的私有設定裡，關閉 Android 備份。金鑰不會進入畫面；只有原生程式在呼叫供應商時加在請求標頭。供應商的錯誤訊息若帶出金鑰，會先換成「[已隱藏]」。
- 模型只從供應商回報的清單選擇，不猜測名稱。預設選清單裡最新的快速小模型（Claude 的 Haiku 系列、OpenAI 的 mini 系列），可以在設定改成任何列出的模型。OpenAI 的清單只保留能對話與使用工具的模型。
- 每輪最多 16 次工具呼叫；先前的對話只以「需求／回覆」的文字帶入，所以中途可以換供應商。

## 架構與安全界線

| 部分 | 位置 | 說明 |
| --- | --- | --- |
| 原生殼層 | `android/haiku/src/com/moriteahouse/cca/haiku/MainActivity.java` | 兩個 WebView：編輯頁載入 App 內建的資源（`https://haiku.app/`），只有它有 `HaikuHost` 橋接；預覽頁從專案資料夾讀檔（`https://haiku.preview/<專案>/`），**沒有**橋接，所以 AI 寫出的程式不能呼叫主機、讀取金鑰或替你花錢 |
| 專案與還原點 | `core/Projects.java` | 純 Java。路徑只接受專案內的相對路徑：拒絕 `..`、絕對路徑、反斜線、磁碟代號、連結與 `.haiku` 內部資料夾；單檔 2 MiB、每個專案 400 個檔案 |
| 供應商連線 | `core/ModelClient.java` | 純 Java 的 HTTPS 呼叫，請求與回應本文原樣轉交，金鑰只放標頭 |
| 金鑰 | `KeyVault.java` | Android Keystore 加密 |
| 介面 | `android/haiku/web/` | 手機版編輯頁；模型輸出一律當文字顯示，只把程式碼區塊與行內程式碼排版，不當成 HTML。CSP 禁止頁面自行連網 |

App 只要求網路權限，不讀寫手機的其他檔案；匯出 ZIP 透過 MediaStore 寫入下載資料夾，不需要儲存權限。最低 Android 10（API 29），目標 API 35。

## 安裝

Haiku 的安裝包放在電腦的 `%APPDATA%\CCA\installers\haiku-<版本>\CCA-Haiku-<版本>.apk`。Windows 版 CCA 的「軟體更新」會在「Android 版本」一區列出它和路徑，但不提供安裝按鈕。把 APK 傳到手機（傳輸線、雲端硬碟或通訊軟體都可以），在手機上開啟並允許「安裝不明應用程式」。更新時安裝新的 APK 即可，專案保留。

APK 以 `%USERPROFILE%\.android\cca-haiku.jks` 簽署；Android 只接受同一把金鑰簽署的更新，這個檔案要保留，不要放進版本控制。

## 建置與測試

```powershell
python android\haiku\build.py --test   # 純 Java 核心：路徑、檔案、還原點、匯出、HTTPS 與金鑰遮蔽
python android\haiku\build.py          # 產生 build\android\CCA-Haiku-<版本>.apk
node tests\haiku_browser.cjs            # 手機尺寸的編輯頁（Edge），使用 web\mock-host.js
python tools\make_release.py --apk build\android\CCA-Haiku-0.0.2.apk --edition haiku --output "%APPDATA%\CCA\installers\haiku-0.0.2"
```

建置只用 JDK 17 與 Android SDK 的 build-tools 35.0.0、platforms;android-35（`%LOCALAPPDATA%\Android\Sdk` 或 `ANDROID_HOME`），不需要 Gradle 或 Android Studio。版本號寫在 `android/haiku/AndroidManifest.xml` 與 `web/haiku.js`。

## 限制

- 只支援網頁專案；不能在手機上執行 Python、Node 或編譯其他語言。
- 需要網路與 API 金鑰；沒有訂閱登入，也沒有離線模型。
- 單一 AI 對話，沒有群組互審、本地審查員或 CCA 插件。
- 預覽頁可以連網（例如載入 CDN 的程式庫），但沒有橋接。
- 回覆不是串流：每次請求完成後才顯示。

