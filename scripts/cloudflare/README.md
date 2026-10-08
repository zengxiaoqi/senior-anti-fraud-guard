# Cloudflare Tunnel 运维说明

内网穿透已从 **cpolar** 迁移到 **Cloudflare named tunnel**。原来痛点是 cpolar 免费版每次重启隧道都换随机域名，要反复同步小程序 `config.js` 和微信公众平台服务器域名；现在域名永久固定。

## 固定域名

```
https://guard.chataifree.eu.org   ->  http://127.0.0.1:3000
wss://guard.chataifree.eu.org
```

单一真源写在 `scripts/cloudflare/hostname.txt`，改域名同时改这里即可。
小程序侧由 `wechat-miniprogram/config.js` 承载（见下）。

## 为什么选 Cloudflare 而不是继续用 cpolar

本机**入站方向被拦**：cpolar 的模型是「cpolar 服务器反向连回本机」，实测报
`[pxy] Server failed to read StartProxy: read tcp ... i/o timeout`，隧道能建立但外部流量打不进来。

Cloudflare Tunnel 是**纯出站**模型：cloudflared 主动外连 Cloudflare 边缘，并在这条连接上反向转发。
不需要任何入站，正好绕开这个限制。

## 为什么用 token 而不是 cert.pem

`cloudflared tunnel login` 授权本身能成功，但最后一步证书要通过浏览器回调本机，
被系统代理劫持，报 `Failed to fetch resource`， cert.pem 落不了地。

所以改用 **token 方式**（Zero Trust 远程托管隧道）：
- 不需要 cert.pem，也不需要本地 `config.yml`
- 路由规则在 Zero Trust 云端配置
- 代价：`tunnel route dns` 这条 CLI 命令用不了（它依赖 cert.pem），也无法配 `readTimeout` 等高级参数

token 存放位置：`D:\cloudflared\tunnel-token.txt`（单行，**在仓库外，不进版本库**）。
需要本地 CLI 管理时，可临时关掉系统代理重跑 `cloudflared tunnel login` 换取 cert.pem。

> **装成服务后，token 会被 cloudflared 复制走**，服务的启动参数变成
> `cloudflared.exe tunnel run --token-file C:\ProgramData\cloudflared\token`。
> 也就是说日常运行不再依赖 `D:\cloudflared\tunnel-token.txt`；那份只是给
> `run-tunnel-headless.bat`（计划任务路线）兜底用的。

## 连通性与性能实测（2026-10-08）

同一台机器、同一个 **7,347,980 字节（7.01MB）** 录音文件：

| 路径 | 耗时 | 吞吐 |
|---|---|---|
| 本机直连 `127.0.0.1:3000` | 1.4s | 5.26 MB/s |
| **Cloudflare Tunnel** | **85.5s** | **86 KB/s** |
| （历史）cpolar 隧道 | 58s | 127 KB/s |

**吞吐方差极大，别用单次测量下结论。** 同一条链路不同时刻差异可达 2~3 倍：

| 样本 | 实测 |
|---|---|
| 3.15MB | 15.7s / **199 KB/s** |
| 7.35MB（白天） | 72~85s / 86~102 KB/s |
| 真机 7.01MB × 3 次 | **29s / 93s / 95s** |

边缘节点由 CF 动态选定，`CF-RAY` 末三位见过 `LAX`，也见过 `SEA`。

**风险**：最慢的几次（95s）已经逼近 Cloudflare 边缘约 100s 的请求时限，
一旦被掐断，这段录音证据就没了。

**缓解手段（已实施，2026-10-08）**：单段录音时长默认从 10 分钟下调到 **5 分钟**，
配置项 `GuardConfig.recordingSegmentMinutes`（可调 1~10 分钟），并随防护规则一起做云端同步。
单段减半后，最坏情况的传输窗口也从 ~95s 压到约 50s。
老人端的上传队列本身带退避重试，偶发失败不会丢证据。

连通性预检里 `region2.v2.argotunnel.com` 的 UDP QUIC 会 FAIL（UDP 7844 部分不可达），
cloudflared 自动降级到 http2，不影响实际注册成功。

## 本机 DNS 的坑

新域名在 **阿里 223.5.5.5 / 腾讯 119.29.29.29 / 114 / 8.8.8.8 / 1.1.1.1 全部正常解析**，
唯独本机的上级 DNS（路由器 `192.168.1.1`）会返回 NOERROR 但 0 条应答。
把网卡 DNS 改成 `223.5.5.5` 可根治，但需要管理员权限。

受影响的只有这台开发机上的命令行工具；手机 / 小程序走公网 DNS 不受影响。

因此工具链统一改成**不依赖本机解析器**：

- `scripts/mp-publish/set-domain.js` — 显式指定公共 DNS 解析，再用 IP 直连 + `Host` 头 + SNI 验证
- `scripts/cloudflare/check-ws.js` — 同上，验证 `wss` 能否穿透

> Git Bash 下 `ipconfig /flushdns` 会被 MSYS 路径转换吃掉，必须写成
> `MSYS_NO_PATHCONV=1 ipconfig /flushdns`

## 脚本编码铁律

**本项目的 `.bat` / `.ps1` 一律 ASCII-only。**

cmd.exe 在 `chcp 65001` 生效之前用 GBK 去解 UTF-8 字节，中文行会被解成乱码并直接变成非法命令
（报错形如 `'xxx' 不是内部或外部命令`）。踩过一次，别再犯。

交付前校验（必须为空）：

```bash
python -c "d=open('脚本路径.bat','rb').read(); print([b for b in d if b>127])"
```

PowerShell 处理中文源码也同理 —— 用工具写 UTF-8 容易写坏，统一不在脚本里用中文。

## 常用操作

```bash
# 改域名：写 hostname.txt + 自动改小程序 config.js + 公网自检
node scripts/mp-publish/set-domain.js

# 验证 WebSocket 穿透
node scripts/cloudflare/check-ws.js

# 看隧道状态
sc query Cloudflared

# 手动前台跑（调试用，Ctrl+C 退出）
D:\cloudflared\cloudflared.exe --no-autoupdate tunnel run --token <token>
```

## 持久化：两种模式

**A. Windows 服务（需管理员，一次搞定）**

右键「以管理员身份运行」：

```bat
scripts\cloudflare\install-by-token.bat eyJhIjoiXXXX...
```

开机自启 + 崩溃自动重启（`sc failure` 三次重试，间隔 60s）。

安装时 cloudflared 通常会自己把服务拉起来，所以你会看到：

```
[4/4] Enabling autostart and starting ...
请求的服务已经启动。   (NET HELPMSG 2182)
```

**这是成功不是失败** —— 2182 的含义就是「服务已在运行」。脚本已修正误判，并会额外
确认状态真的是 RUNNING 才放行。

正常情况下最终应该有**且仅有**一个 cloudflared 进程，且它的父进程是 `services.exe`
（说明由 SCM 持有，能随开机自启）：

```powershell
Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" | Select ProcessId, ParentProcessId
```

如果还留着一个以前手工前台跑的实例，杀掉它避免重复：

```bash
MSYS_NO_PATHCONV=1 taskkill /PID <pid> /F
```

**B. 计划任务（不需要管理员）**

沿用 cpolar 当年那套：`start-server-bg.bat` 前台运行 cloudflared，由计划任务 `AntiFraudGuardBackend` 持有进程树。
好处是不用提权；代价是进程归属受限于计划任务的触发时机。

> 注意：`start-server-bg.bat` 里有一处历史坑 —— 原逻辑在「3000 端口已在监听」时提前 `exit /b 0`，
> 导致后面的隧道段永远执行不到（后端活着、隧道死了没人拉起）。已修复为各段独立判断。

两个实例同时跑同一条隧道是被 Cloudflare 允许的（多副本），但没必要，选一种即可。

## 别忘了：微信公众平台

换域名后必须在「开发 → 开发管理 → 开发设置 → 服务器域名」同步，否则真机会拦截请求：

```bash
scripts\mp-publish\sync-wx-domain.bat    # 需管理员扫码一次
```
