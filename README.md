# VPN Hub

Android 16 或以上嘅 Virtual Private Network（VPN）App。每小時自動搜集網上公開嘅 VLESS、VMess、Trojan、Hysteria2、WireGuard、OpenVPN 節點，用真實核心逐個測試，移除唔通嘅，再按出口國家分類。

## 點樣運作

```
GitHub Actions（每小時）                         你部手機
┌──────────────────────────────┐              ┌──────────────────────────┐
│ 1. 抓取 sources.txt 嘅來源     │              │ VPN Hub App               │
│ 2. 解析、去重                  │   nodes.json │  · 每小時下載最新清單       │
│ 3. sing-box 實測（經代理開      │ ───────────► │  · 按國家顯示、揀節點       │
│    Cloudflare 頁面，攞出口國家）│  (nodes 分支) │  · 自動喺最快幾個之間切換   │
│ 4. OpenVPN 真實握手測試         │              │  · 斷線節點下次更新自動移除 │
│ 5. 發佈到 nodes 分支            │              └──────────────────────────┘
└──────────────────────────────┘
```

- 國家係按「出口 Internet Protocol（IP）地址」判斷，即係網站見到你喺邊，唔係節點伺服器登記喺邊。
- VLESS／VMess／Trojan／Hysteria2／WireGuard 由 App 內置嘅 sing-box 核心直接連線。
- OpenVPN：sing-box 冇 OpenVPN 引擎，App 會經官方接口交畀免費嘅「OpenVPN for Android」連線（第一次撳 OpenVPN 節點會提你安裝同授權）。
- WireGuard：公開清單幾乎冇 WireGuard 節點，所以自動註冊 Cloudflare WARP（顯示為「Cloudflare WARP」，出口係就近嘅 Cloudflare 機房）。

## 安裝步驟（用手機都做到）

1. 登入 GitHub，建立一個 **Public（公開）** repository，例如 `vpn-hub`。
   公開先有免費無限 Actions 分鐘數，App 亦要公開網址先下載到節點清單。
2. 將呢個資料夾所有檔案上傳到 repo 嘅 `main` 分支（保持資料夾結構，包括 `.github/`）。
3. 去 repo 嘅 **Settings → Actions → General → Workflow permissions**，揀 **Read and write permissions**，儲存。
4. 去 **Actions** 分頁：
   - 執行 **Update nodes**（Run workflow）→ 約 15 至 40 分鐘後會出現 `nodes` 分支。
   - 執行 **Build APK** → 第一次要編譯核心，約 20 至 40 分鐘；之後有快取會快好多。
5. 完成後去 repo 右邊 **Releases**，下載 `VPNHub.apk` 安裝（要容許「安裝未知來源 App」）。
6. 打開 App → 撳「連線」→ 允許 VPN 權限。

App 已經自動填好你 repo 嘅節點清單網址；如要改，去 App 右上角「設定」。

## 日常用法

- **⚡ 全部最快（自動）**：喺全部最快嘅 20 個節點之間自動揀，斷咗自動換。
- **撳國家**：只用嗰個國家嘅節點（自動揀最快）。
- **撳國家右邊箭咀**：展開節點清單，可以指定單一節點。
- **頂部協定按鈕**：只顯示／使用某幾種協定。
- 快捷設定（通知欄下拉）可以加「VPN Hub」開關。

## 加自己嘅來源

改 `collector/custom_sources.txt`，一行一個：

```
https://example.com/my-subscription        # 訂閱網址（純文字或 base64）
ovpn https://example.com/server.ovpn       # 單一 OpenVPN 檔
```

儲存後會自動重新測試。

## 其他

- 每個國家另有標準訂閱檔：`https://raw.githubusercontent.com/<你>/<repo>/nodes/sub/HK.txt`（`all.txt` 係全部），可以畀 v2rayNG、NekoBox 等其他 App 用。
- 測試參數可以喺 `update-nodes.yml` 加環境變數調整：`MAX_PER_PROTOCOL`（每種協定最多測幾多個，預設 2500）、`PER_COUNTRY`（每國最多保留幾多個，預設 150）、`TEST_TIMEOUT`（秒，預設 10）。
- 簽名金鑰 `android/app/vpnhub.keystore` 係固定嘅，所以新版可以直接覆蓋安裝。repo 公開即係人人都睇到呢個金鑰；如介意，可以自行換一個，並用 Secrets 設定 `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
- GitHub 規定公開 repo 如果 60 日完全冇活動，排程 workflow 會被暫停；去 Actions 撳一下 Enable 就得。

## 安全提示

網上公開嘅免費節點來歷不明，營運者理論上睇得到未加密嘅流量，部分甚至可能係刻意設置嚟收集資料。請只用嚟處理唔敏感嘅瀏覽，唔好用嚟登入工作系統、網上銀行或者處理任何機密資料。

## 授權

App 使用 sing-box（GPL-3.0-or-later），部分平台介面寫法參考 sing-box-for-android（GPL-3.0），所以整個 App 以 GPL-3.0 發佈。OpenVPN 接口定義來自 ics-openvpn。
