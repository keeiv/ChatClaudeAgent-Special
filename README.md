<p align="center">
  <img src="assets/editions/haiku/icon-256.png" alt="Haiku 產品圖標" width="160">
</p>

<h1 align="center">ChatClaudeAgent 俳句 Haiku</h1>

<p align="center">
  <b>在手機上輕鬆編程。</b><br>
  只在 Android 使用的特殊版本。這不是遠端操作電腦。
</p>

<p align="center">版本 0.0.2 · Android 10 以上 · <a href="LICENSE">GPL-3.0</a></p>

## 它做什麼

專案存在手機裡。告訴 Haiku 你想做什麼，它直接寫入網頁專案的檔案，按「預覽」立刻在手機上看到結果。

- **網頁專案與即時預覽**：HTML、CSS、JavaScript；預覽在沒有主機橋接的獨立視窗執行，AI 寫的頁面不能呼叫手機的功能或讀取金鑰。
- **用你自己的 API 金鑰**：支援 Claude（Anthropic API）與 GPT（OpenAI API），依用量計費，和訂閱分開。金鑰以 Android Keystore 加密，只在原生程式送出請求時加上，不會進入網頁。
- **每一輪都能還原**：每輪開始前先存下專案，最近 20 個；不滿意就按「還原這一輪」。
- **可帶走**：專案可匯出成 ZIP。
- 模型只從供應商回報的清單選擇，預設選最新的小模型。

限制：只支援網頁專案；需要網路與 API 金鑰；回覆不是串流；單一 AI 對話，沒有群組互審。完整說明見 [docs/haiku.md](docs/haiku.md)。

## 安裝

從 [Releases](../../releases) 下載 `CCA-Haiku-<版本>.apk`，傳到手機開啟，並允許「安裝不明應用程式」。更新時安裝新的 APK 即可，專案保留。
APK 以同一把金鑰簽署，Android 只接受同一把金鑰簽署的更新。

## 建置與測試

需要 JDK 17 與 Android SDK（build-tools 35.0.0、platforms;android-35）；不需要 Gradle 或 Android Studio。

```bash
python android/haiku/build.py --test   # 純 Java 核心：路徑、檔案、還原點、匯出、HTTPS 與金鑰遮蔽
python android/haiku/build.py          # 產生 build/android/CCA-Haiku-<版本>.apk（需要自己的簽署金鑰）
node tests/haiku_browser.cjs           # 手機尺寸的編輯頁（需要 Playwright 與 Edge）
```

第一次建置會在 `~/.android/` 建立自己的簽署金鑰；簽署金鑰不要放進版本控制，遺失後手機無法更新。

## 授權

以 [GNU GPL v3](LICENSE) 授權。普通版本與特殊版本的關係見
[版本路線](https://github.com/keeiv/ChatClaudeAgent/blob/main/docs/editions.md)。
