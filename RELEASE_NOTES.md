# 更新紀錄

每次 App 有改動，最頂嗰段會自動顯示喺 GitHub Releases 嗰個版本度。

## 2026-10-10 · 網速、自動檢查更新、分流、每 10 分鐘重測

- 主頁同通知列顯示實時上傳／下載速度，同埋今次連線用咗幾多流量。
- 每次打開 App 會自動檢查 GitHub 有冇新版本，有就彈出更新內容，撳「下載更新」即可。
- 新增「分流」：可以指定某啲 App 用某個國家，例如 LINE 用日本、某 App 用菲律賓，亦可以設定某啲 App 唔經 VPN。只喺 VPN 模式生效。
- 雲端每 10 分鐘重測一次所有節點（以前係每小時）。
- 連線期間 App 每 10 分鐘自動攞最新節點清單，唔使斷線。
- 由呢個版本開始先有自動檢查更新，所以今次要自己手動下載安裝一次。

## 2026-10-10 · 支援更多協定

- 新增 9 種協定：Shadowsocks、Hysteria（第一代）、TUIC、AnyTLS、NaiveProxy、Snell、SSH、SOCKS5、HTTP。連同原有 6 種，合共 15 種。
- 支援 Clash／Clash.Meta YAML 格式嘅訂閱，以及純 IP:端口 嘅 SOCKS5／HTTP 代理清單。
- 新增 Clash 訂閱、TUIC、Shadowsocks 同公開 SOCKS5／HTTP 代理等來源。
- 協定篩選按鈕加入新協定；之後再加新協定都會自動顯示。
- 注意：SOCKS5 同 HTTP 公開代理本身冇加密，營運者睇得到未加密內容。

## 2026-10-10 · OpenVPN（VPN Gate）喺 App 內直接連線

- OpenVPN 節點而家喺 App 入面直接連線，唔再需要另外安裝「OpenVPN for Android」。
- OpenVPN 節點同其他節點一樣，可以揀國家自動切換、指定單一節點，亦支援代理模式。
- VPN Gate 伺服器唔再只測評分最高 80 個，清單有幾多就測幾多。
- OpenVPN 節點改為用實測出口國家分類，並顯示 IP 類型。
- VPN 核心升級至 sing-box 1.14.3。

## 2026-10-10 · Releases 顯示更新內容

- GitHub Releases 每個版本會列出今次更新咗乜嘢。
- App 功能同上一版一樣，唔使特登更新。

## 2026-10-10 · 代理模式、優先國家

- 新增「代理模式」：唔開 VPN，喺 `127.0.0.1:10808`（端口可改）開 SOCKS5／HTTP 代理，可以畀 AdGuard 等 App 使用。
- 代理模式可選擇容許同一 Wi-Fi／熱點嘅其他裝置連線。
- 連線中途切換模式會自動斷開再重新連線。
- 節點測試優先搜集菲律賓、印度、土耳其、阿根廷、哈薩克斯坦嘅節點，並加入 15 個按國家分類嘅來源。

## 2026-10-10 · IP 類型

- 每個節點顯示出口 IP 類型（Data Centre／Residential／ISP／Mobile）同網絡供應商名稱。
- 新增 IP 類型篩選按鈕，自動揀最快時只會用符合篩選嘅節點。

## 2026-10-09 · 首個版本

- 每小時自動搜集同測試 VLESS、VMess、Trojan、Hysteria2、WireGuard、OpenVPN 節點，按出口國家分類。
- 可以揀「全部最快」、指定國家或者指定單一節點。
- OpenVPN 節點經「OpenVPN for Android」連線。
