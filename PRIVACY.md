<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

## 隱私權政策

**同文**爲開源安卓漢語輸入法項目，讀取以下資訊是爲了協助使用者更快速的輸入文字。
應用本身不會蒐集和上傳任何個人隱私數據至任何外部服務和伺服器，尤其用戶的輸入數據。

讀取如下資訊和權限：

* 儲存空間（必要）：用於公共 `/Rime` 資料夾內的使用者詞典與設定檔。Android 11 以上使用「所有檔案存取權」，Android 10 以下使用儲存空間權限；應用只將 `/Rime` 作為 Rime 資料來源。
* 麥克風（選用）：用於系統語音輸入，如不需要可關閉。
* 手寫辨識模型（選用）：中文簡體 `zh-Hani-CN`、中文繁體 `zh-Hani-TW`、日文 `ja`、英文 `en-US` 使用官方 ML Kit Digital Ink Recognition 裝置端模型。第一次使用相應語言時由 Google 的模型服務按需下載，每個模型約 20MB；下載需要網路，下載失敗或離線時可重試。模型就緒後，筆畫與候選辨識都只在裝置上處理，不會上傳手寫內容。切換简/繁會選擇對應中文模型。模型下載不要求額外 Android 權限。

2026年11月3日更新

## Privacy Policy

**TRIME** is an open source project for Android input method.
It won't collect or upload any user's privacy to any external service or server especially the user's input history.

Permissions application needs:

* Storage (REQUIRED): Used for user dictionaries and configuration in the public `/Rime` folder. Android 11 and newer use All files access; Android 10 and older use the storage permission. The app uses only `/Rime` as its Rime data source.
* Microphone (OPTIONAL): Used for system voice input and can be disabled when not needed.
* Handwriting models (OPTIONAL): Simplified Chinese `zh-Hani-CN`, Traditional Chinese `zh-Hani-TW`, Japanese `ja`, and English `en-US` use the official on-device ML Kit Digital Ink Recognition models. The corresponding model is downloaded on demand from Google's model service on first use and uses about 20MB per language. Downloading requires a network connection; an offline or failed download can be retried. Once ready, strokes and candidate recognition are processed only on the device and handwriting content is not uploaded. The 简/繁 switch selects the corresponding Chinese model. Model download needs no additional Android permission.

Updated on 2026-11-03
