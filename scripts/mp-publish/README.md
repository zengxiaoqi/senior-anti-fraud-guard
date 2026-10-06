# 小程序发布工具链

发布微信小程序用的脚本，随项目走，路径全部相对推导（换机器、换目录直接可用）。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `config.js`（在 `wechat-miniprogram/`） | **后端域名唯一真源**，只填纯域名，https/wss 自动拼接 |
| `paths.js` | 公共路径与配置，所有脚本共用 |
| `set-domain.js` | 改写 `config.js` 的域名（可从 cpolar 日志自动取最新）+ 直连自检 |
| `upload.js` | 上传小程序代码到微信 |
| `sync-wx-domain.js` | 用 CDP 自动同步公众平台的服务器域名（需扫码一次） |
| `publish.bat` | 一条命令完成「改域名 + 上传」 |
| `sync-wx-domain.bat` | 一条命令完成「改后台服务器域名」 |
| `force_ipv4.js` | 强制 DNS 只解析 IPv4（上传必需，勿删） |
| `cdp.js` | 浏览器调试通用工具（eval / 点击 / 截图），排障用 |
| `check_exp_owner2.js` | 确认体验版当前指向哪个版本 |

## 换域名的标准流程

> cpolar 免费版每次重启隧道都会换域名，所以这套流程会经常用到。

**第一步：改配置 + 上传（全自动）**

```bat
scripts\mp-publish\publish.bat                              :: 域名自动取 cpolar 日志最新一条
scripts\mp-publish\publish.bat xxx.r25.cpolar.top 1.0.3     :: 指定域名 + 版本号
scripts\mp-publish\publish.bat api.example.com 1.0.0 "正式域名"
```

**第二步：同步微信后台服务器域名（需扫一次码）**

```bat
scripts\mp-publish\sync-wx-domain.bat
```

自动走完：进开发设置 → 滚到服务器域名 → 点修改 → 填 5 个输入框
（request / socket / uploadFile / downloadFile / DNS 预解析）→ 保存并提交 →
弹出管理员扫码二维码 → 扫码后自动校验。

> 后台服务器域名**每月只能改 50 次**，次数显示在「修改」按钮旁。
> 自测阶段可在微信开发者工具勾选「不校验合法域名」做真机调试，跳过这一步。

## 环境变量（都有默认值，按需覆盖）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MP_KEY_PATH` | `D:/mp-ci/private.key` | 小程序上传密钥 |
| `MP_WS_FILE` | `D:/mp-ci/.ws` | Chrome 调试会话 wsUrl 文件 |
| `MP_CPOLAR_LOG` | `D:/cpolar-tunnel/cpolar.log` | cpolar 日志，用于自动取域名 |
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

## 排障备忘

- **curl 测 cpolar 域名返回 000 / exit 35 ≠ 隧道断了**：本机 schannel 吊销检查
  （`CRYPT_E_REVOCATION_OFFLINE`）被代理干扰会误报。用 `set-domain.js` 的直连自检，
  或 Python `ssl.wrap_socket` 直连，才能测出真实状态。
- 上传报 `invalid ip: 2409:...`：走了临时 IPv6。`publish.bat` 已带 `--dns-result-order=ipv4first`
  和 `force_ipv4.js`，别绕过它直接跑 `upload.js`。
- 真机报「网络异常」：先跑 `set-domain.js` 看自检是否 200，再看后台服务器域名是否同步。
- 确认体验版指向哪个版本：`cdp.js eval <wsUrl> check_exp_owner2.js`
  （用版本号与「体验版」标签的 y 坐标相邻判断归属）。

## 与 `D:/mp-ci` 的关系

`D:/mp-ci` 是早期临时目录，现只保留两样东西：`private.key`（密钥）和 `.ws`（浏览器会话）。
发布脚本本身已全部迁入本项目，那里剩下的调试脚本是过程产物，可忽略。
