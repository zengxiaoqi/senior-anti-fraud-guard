# 小程序发布工具链

发布微信小程序用的脚本，随项目走，路径全部相对推导（换机器、换目录直接可用）。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `config.js`（在 `wechat-miniprogram/`） | **后端域名唯一真源**，只填纯域名，https/wss 自动拼接 |
| `paths.js` | 公共路径与配置，所有脚本共用 |
| `set-domain.js` | 改写 `config.js` 的域名（默认取 `scripts\cloudflare\hostname.txt`）+ 直连自检 |
| `upload.js` | 上传小程序代码到微信 |
| `sync-wx-domain.js` | 用 CDP 自动同步公众平台的服务器域名（需扫码一次） |
| `open-wx-console.bat` | 用**固定调试端口 9222** 拉起专用 Chrome（同步域名前的准备步骤） |
| `publish.bat` | 一条命令完成「写域名 + 上传」 |
| `sync-wx-domain.bat` | 一条命令完成「改后台服务器域名」 |
| `force_ipv4.js` | 强制 DNS 只解析 IPv4（上传必需，勿删） |
| `cdp.js` | 浏览器调试通用工具（eval / 点击 / 截图），排障用 |
| `check_exp_owner2.js` | 确认体验版当前指向哪个版本 |

## 日常发布流程

> 已迁移到 Cloudflare named tunnel，公网域名**固定为 `guard.chataifree.eu.org`**，
> 不再随隧道重启变化。所以「换域名」这套动作正常情况下**不需要再跑**，
> 只有真正换域名时才用到。

**第一步：写域名 + 上传代码（全自动）**

```bat
scripts\mp-publish\publish.bat                          :: 域名取 scripts\cloudflare\hostname.txt
scripts\mp-publish\publish.bat guard.example.com 1.0.3  :: 指定域名 + 版本号
scripts\mp-publish\publish.bat guard.example.com 1.0.0 "版本说明"
```

**第二步：同步微信后台服务器域名（换域名时才需要，要扫一次码）**

```bat
scripts\mp-publish\open-wx-console.bat   :: 拉起固定端口 9222 的 Chrome，登录公众平台
scripts\mp-publish\sync-wx-domain.bat    :: 再跑这条
```

`sync-wx-domain.js` 会自动向 `http://127.0.0.1:9222/json/version` 取最新 wsUrl 并写回
`MP_WS_FILE`，**不需要手工复制粘贴**。

> **为什么必须用 `open-wx-console.bat` 开 Chrome**：Chrome 若不以固定
> `--remote-debugging-port` 启动（或该端口被另一实例抢占），会退化成随机临时端口，
> wsUrl 形如 `ws://127.0.0.1:49930/...`，会话一结束即失效 —— 表现为
> `无法连接浏览器调试端口: Received network error or non-101 status code.`。
> 该脚本用独立 `--user-data-dir=D:\mp-ci\chrome-profile`，既不抢你日常在用的 Chrome，
> 登录态也会保留。

自动走完：进开发设置 → 滚到服务器域名 → 点修改 → 填 5 个输入框
（request / socket / uploadFile / downloadFile / DNS 预解析）→ 保存并提交 →
弹出管理员扫码二维码 → 扫码后自动校验。

> 后台服务器域名**每月只能改 50 次**，次数显示在「修改」按钮旁。
> 自测阶段可在微信开发者工具勾选「不校验合法域名」做真机调试，跳过这一步。

## 环境变量（都有默认值，按需覆盖）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MP_KEY_PATH` | `D:/mp-ci/private.key` | 小程序上传密钥 |
| `MP_WS_FILE` | `D:/mp-ci/.ws` | Chrome 调试会话 wsUrl 文件（脚本会自动刷新，**不用手填**） |
| `MP_DEBUG_PORT` | `9222` | Chrome 远程调试端口，须与 `open-wx-console.bat` 一致 |
| `MP_CI_MODULES` | `D:/mp-ci/node_modules` | miniprogram-ci 所在 node_modules |
| `MP_QR_OUT` | 系统临时目录 | 扫码二维码输出路径 |

**为什么密钥放在项目外**：本项目是 git 仓库，`private.key` 是等同于部署密码的上传凭证，
放进来一次 `git add .` 就会泄到远端。`.gitignore` 里已加 `*.key` / `*.pem` / `.env` 兜底，
但密钥文件本身仍建议留在项目外。

`miniprogram-ci` 也没装进项目依赖（它只服务于上传，跟后端无关）。
想彻底项目内自洽，可执行 `npm i -D miniprogram-ci`，脚本会自动优先用项目里的。

## 前置条件

1. 上传密钥已下载并放到 `MP_KEY_PATH`
2. 公众平台「开发 → 开发管理 → 开发设置 → IP 白名单」里有本机公网 IPv4（当前 `120.228.150.63`，换网络需更新）
3. 本机 3000 端口的后端服务在跑，隧道已建立

## 排障备忘（都是踩过的坑，别退回旧写法）

### 「扫了码确认了，但域名没变」

最坑的一种失败——配额扣了、二维码也扫了，域名纹丝不动。判断依据：

```
本月还可修改 N 次   ← N 变小 = 提交已被微信服务端接受
域名列表还是旧的     ← 说明送上去的仍是旧值
```

原因：`.url_area` 是 **contenteditable DIV**，`el.textContent = ...` 或
`document.execCommand('insertText')` 只改了 DOM 外观，**没写进页面框架的数据模型**。
提交时读的是模型里的旧值，于是等于提交了一份"没有变化"的申请。

正解：走 CDP 原生输入通道 `Input.insertText`（等同输入法上屏），并且
**每个字段都要读回比对**。实测 5 个字段里常有 2~4 个第一次不生效，需要重试，
所以 `sync-wx-domain.js` 里 `fillOne()` 带三级退避（整段上屏 ×2 + 逐字符敲击），
任一字段没通过就**中止并且绝不点提交**，避免白扣配额（每月只有 50 次）。

### 等待扫码期间的三个「不能」

1. **不能重载/导航页面** —— 会销毁待确认的二维码弹窗，扫了也是白扫。
   脚本改为只读弹窗文案，靠它消失判断确认完成。
2. **不能 blur / 点空白** —— 该弹窗是 trap-focus 设计，一失焦就整关掉。
3. **不能同时跑第二个会碰页面的进程** —— 曾经有个后台轮询为了检测状态每 8 秒
   `reload()` 一次，把正在填写的弹窗反复冲掉，症状表现为 `NO_DIALOG`、
   "填了又丢"，极难联想到是这个原因。**同一时刻只允许一个东西操作浏览器。**

### 弹窗定位别死守 class 名

同一弹窗在不同渲染批次下 class 可能是 `weui-desktop-dialog` /
`weui-desktop-dialog_self` / `..._wrp`。用 `.class` 选择器会时灵时不灵。
脚本已改为按可见文案「配置服务器域名」匹配 `[class*=dialog]`，
再按文本长度升序取最内层，并要求至少有 5 个 `.url_area` 才算配置弹窗本体。
- **`.bat` 报 `'xxx' 不是内部或外部命令` 且文字是乱码**：典型的编码炸裂。
  cmd 在 `chcp` 生效前用 GBK 解 UTF-8 字节，多行中文会被解成非法命令。
  **本目录所有 `.bat` 必须 ASCII-only**，中文注释请写在 JS/MD 里。
- 上传报 `invalid ip: 2409:...`：走了临时 IPv6。`publish.bat` 已带 `--dns-result-order=ipv4first`
  和 `force_ipv4.js`，别绕过它直接跑 `upload.js`。
- 真机报「网络异常」：先跑 `set-domain.js` 看自检是否 200，再看后台服务器域名是否同步。
- 确认体验版指向哪个版本：`cdp.js eval <wsUrl> check_exp_owner2.js`
  （用版本号与「体验版」标签的 y 坐标相邻判断归属）。

## 与 `D:/mp-ci` 的关系

`D:/mp-ci` 是早期临时目录，现只保留：`private.key`（密钥）、`.ws`（调试会话 wsUrl，
脚本自动维护）和 `chrome-profile/`（专用 Chrome 的登录态）。
发布脚本本身已全部迁入本项目，那里剩下的调试脚本是过程产物，可忽略。
