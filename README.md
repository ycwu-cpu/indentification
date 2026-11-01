# 車牌記錄 App（Android）

WebView 殼 + HyperLPR3 原生車牌辨識。所有原 HTML 功能保留（地點管理、歷史查詢、月報表、Excel、EmailJS 寄信），拍照辨識改由 HyperLPR3 離線深度學習引擎處理。

## 建置 APK（不需安裝 Android Studio）

1. 在 GitHub 建立新 repository，把本專案所有檔案上傳（可用網頁「Add file → Upload files」）
2. 到 Actions 分頁 → 選 "Build APK" → "Run workflow"
3. 約 5 分鐘後，在該次執行頁面底部的 Artifacts 下載 `plate-app-apk`
4. 把 `app-debug.apk` 傳到手機安裝（需允許「安裝未知來源應用程式」）

## 本機建置（選項）

安裝 Android Studio 後開啟專案 → Build → Build Bundle(s)/APK(s)。

## 使用注意

- 首次辨識前需連線一次（Tesseract 引擎與報表函式庫為 CDN 載入）；HyperLPR3 模型已內建，完全離線
- 拍照後由 HyperLPR3 自動尋找車牌；找不到時可用框選 + Tesseract 備援，或手動輸入
- EmailJS 設定在「月報表」頁面，設定後可全自動寄信
