# 外來檔案與來源

本倉整體採 **TBWTFPL**（見根目錄 `LICENSE`，來源 <https://github.com/Capricornus007/tbwtfpl>；
其第 2 條明文無視其他授權檔的限制）。下表是**搬進來的現成程式碼**，
每個目錄裡都附了來源倉的授權檔原件，僅供追溯出處。

| 目錄 | 來源（倉／commit） | 原始授權 | 搬進來的是什麼 |
|---|---|---|---|
| `third_party/trime-jni/` | `osfans/trime` | GPL-3.0 | **NDK 交叉編那一串的黏合層**：`CMakeLists.txt` 加 `cmake/` 下的 `Boost.cmake`、`FindBoost`、`FindGlog`、`FindLevelDb`、`FindMarisa`、`FindOpencc`、`FindYamlCpp`、`OpenccWorkarounds`、`Rime.cmake`，以及 `librime_jni/CMakeLists.txt`。這是「自己從零搭 Android 端」最花時間的一块（librime 要帶 OpenCC／snappy／glog／yaml-cpp／marisa／leveldb／boost 一起交叉編），直接沿用他們跑過的寫法。注意它原來的子模組路徑是 `app/src/main/jni/{librime,OpenCC,snappy,...}`，我們自己的專案要重新對應。 |
| `third_party/xime-t9/` | `ximeiorg/Xime` | GPL-3.0 | 九鍵引擎：`librime-t9/`（`t9_state_machine`、`t9_digit_segment_strategy`、**`t9_digit_userdict`（九鍵專屬的用戶詞頻持久）**、`t9_right_commit_handler`、`t9_panel_state`）＋ Kotlin 側 `T9InputController`、`T9DisplayHelper`、`T9KeyboardLayout`、`T9KeyboardSwipeOverlay` |
| `third_party/fcitx5-t9/` | `Arcns/fcitx5-android-t9` | LGPL-2.1 | 九鍵 UI 行為最貼 gboard 的一組：`T9SideRailUi`／`T9SideRailComponent`（**左側直式已按碼欄**）、`T9PinyinResolver`（即時消歧）、`T9Coordinator`／`T9SessionReducer`／`T9SessionBridge`、`SwipeSymbolDirection`（四方向滑動選字母）、`PopupPreset`（長按彈出）、`T9Keyboard`，外加兩支 androidTest（Hamster 佈局與長按彈出）當行為規格 |
| `third_party/vertick-scel/` | `BurgerK1ng16/Vertick-IME` | GPL-3.0 | `.scel` 辨識與解析（`looksLikeScel()` 認魔數 0x40、小端解析）＋詞庫包下載匯入（zip＋sha256＋RSA）；另附一份 `weike_t9.schema.yaml` 當第二種九鍵方案寫法 |
| `third_party/trime-9key-config/` | `lida0407/trime-9key`（osfans/trime 的九鍵分支） | GPL-3.0 | 他們的 `trime.yaml` 與 `tongwenfeng.trime.yaml`（**35 處 `t9_pick_*` 鍵位**與 `cycle_mode_t9` 模式鍵）與 `t9_preedit.lua` |
| `third_party/zinnia/` | `wkpark/zinnia`（上游 `taku910/zinnia`） | BSD-3-Clause | 手寫識別引擎的 Android 綁定（`Android.mk` ＋ `libzinniajni.cpp`） |
| `third_party/sogou-spider/` | `StuPeter/Sougou_dict_spider` | MIT | 搜狗詞庫站的抓列表＋分頁＋下載（`SougouSpider.py`） |
| `data/shared/t9_pinyin.schema.yaml`、`t9_dict.dict.yaml`、`lua/t9_preedit.lua` | 同上 `trime-9key`（`t9_dict` 本倉改寫過） | GPL-3.0 | 九鍵方案本體；`t9_dict` 已改成只 `import_tables: [luna_pinyin]`，也就是吃使用者自己那份詞典鏈與 userdb |

## 只讀、未取用（記錄下來免得之後又去撿）

- `HoengSaan/trime-gukwan`：**CC-BY-NC-SA-4.0（非商業）**，而且查清了它是 36 鍵主題、不是九鍵，倉內零鍵盤層程式碼。
- `githubms68/trime-dahi`：**倉內沒有 LICENSE 檔**（法律上預設全部權利保留）。它的 `dahi.patch`（剪貼板搜尋／置頂／拖排／AES-256）只能當行為參考。
- `Mgrsc/LIME`：NOASSERTION（授權未宣告），ONNX 手寫那套只可讀。
- 搜狗輸入法 Moto 版 10.32 與 v11.21：僅逆向觀察。可引用的事實含九鍵鍵盤 `py_9.ini` 排法、
  剪貼板 150 條上限（`dfm.java:268`）、用戶詞典落地目錄 `files/.dict/`、
  簡繁是上屏後 `simToTradWord` 單點轉換、以及「**僅支持日语汉字的手写输入**」。
