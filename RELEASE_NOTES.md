# 更新紀錄

每次 App 有改動，最頂嗰段會自動顯示喺 GitHub Releases 嗰個版本度。

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
