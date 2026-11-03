<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

## 隱私權政策

**同文**爲開源安卓輸入法項目。應用本身不會將輸入歷史或手寫筆畫傳送到開發者的伺服器；官方 ML Kit SDK 的模型下載與診斷資料處理如下披露。

讀取如下資訊和權限：

* 儲存空間（必要）：用於公共 `/Rime` 資料夾內的使用者詞典與設定檔。Android 11 以上使用「所有檔案存取權」，Android 10 以下使用儲存空間權限；應用只將 `/Rime` 作為 Rime 資料來源。
* 麥克風（選用）：用於系統語音輸入，如不需要可關閉。
* 手寫辨識模型（選用）：中文簡體 `zh-Hani-CN`、中文繁體 `zh-Hani-TW`、日文 `ja` 使用官方 ML Kit Digital Ink Recognition 裝置端模型。第一次使用相應語言時由 Google 的模型服務按需下載，每個模型約 20MB；下載需要網路，下載失敗或離線時可重試。模型就緒後，手寫筆畫與候選辨識在裝置上處理，筆畫只暫存在 Android 不備份目錄，不會交給備份服務。依 Google 的 ML Kit 資料披露，SDK 可能向 Google 傳送裝置與應用程式資訊、安裝識別碼、效能與事件指標、輸入/輸出大小、錯誤資訊，以及已設定的 Digital Ink 語言；不會傳送手寫內容本身。切換简/繁會選擇對應中文模型。模型下載不要求額外 Android 權限。詳見 [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) 與 [ML Kit terms](https://developers.google.com/ml-kit/terms)。

2026年11月3日更新

## Privacy Policy

**TRIME** is an open-source Android input method. The app does not send input history or handwriting strokes to a developer-operated server; the official ML Kit SDK's model-download and diagnostic processing is disclosed below.

Permissions application needs:

* Storage (REQUIRED): Used for user dictionaries and configuration in the public `/Rime` folder. Android 11 and newer use All files access; Android 10 and older use the storage permission. The app uses only `/Rime` as its Rime data source.
* Microphone (OPTIONAL): Used for system voice input and can be disabled when not needed.
* Handwriting models (OPTIONAL): Simplified Chinese `zh-Hani-CN`, Traditional Chinese `zh-Hani-TW`, and Japanese `ja` use the official on-device ML Kit Digital Ink Recognition models. The corresponding model is downloaded on demand from Google's model service on first use and uses about 20MB per language. Downloading requires a network connection; an offline or failed download can be retried. Once ready, strokes and candidate recognition are processed on device. Strokes are stored only transiently in Android's no-backup directory and are not sent to backup services. According to Google's ML Kit data disclosure, the SDK may send Google device and application information, installation identifiers, performance and event metrics, input/output sizes, errors, and configured Digital Ink languages; handwriting content itself is not sent. The 简/繁 switch selects the corresponding Chinese model. Model download needs no additional Android permission. See [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [ML Kit terms](https://developers.google.com/ml-kit/terms).

Updated on 2026-11-03
