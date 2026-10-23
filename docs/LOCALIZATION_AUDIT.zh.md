# 重做汉化：逐页显示内容与业务链路核对

## 基线和范围

唯一基线：`a134b8e5e0424024870b53252eb0d1b9f4d3195e`，即本次汉化前的原版。

应用只有两个 Activity 界面：主配置界面 `MainActivity`、拨号恢复界面 `GatewayDialerActivity`。主界面的高级设置是折叠区域，不是另一个页面。系统默认电话应用选择、系统设置、解锁和 Shizuku 授权界面由系统或 Shizuku 提供，本项目不翻译其内部内容。

此版是固定中文的界面文字版，保留原来的产品名称 Voice SIP Agent。与业务共用的状态、错误、日志和协议标识保留原文，因此部分技术状态仍会显示英文。优先保证不处理这些数据，而非追求每个字符均为中文。

上一版的状态解析器、资源转换调用、枚举显示映射和翻译后的校验中间值已全部撤销。以下是源码审查结果，不是已复现用户所报故障的根因结论。

## 主配置界面：按原始界面顺序

| 显示区域 / 控件 | 原始数据或条件 | 点击 / 保存后的业务链路 | 本次处理 |
| --- | --- | --- | --- |
| 产品标题 | 固定 `Voice SIP Agent` | 无 | 原样保留产品身份 |
| 通话接管开关及说明 | `cur.takeover`、pending 标志、`holdsDialer`、`cellularReady` | `setTakeoverFromUi` → `setTakeover` → `TakeoverControl`；需要时请求默认电话应用资格；通话中延后执行 | 仅翻译已有说明文字；所有原条件、回调和 `true/false` 原样保留 |
| 通话访问状态 | `callAccessReady`、`effectiveCallAccess`、`holdsDialer`、SDK 版本 | 只读；不由显示字符串决定是否就绪 | 仅翻译固定标签和原分支说明 |
| 默认电话应用提示、恢复、选择、取消 | `dialerRoleAvailable`、`dialerChangeRunning`、`dialerChangePending`、Shizuku 连接状态 | `requestDialer` / `switchDialer` / `chooseDialerInSettings` / `cancelDialerChange` → 原来的系统和 runtime 流程 | 仅翻译按钮、静态说明；`roleMessage` 直接显示原始值 |
| 本机听筒 / 扬声器静音开关 | `cur.muteEarpiece`；本机输出能力和兼容性标志 | `setMuteEarpiece(it)` 保存设置并清除原有设备兼容性标记；下次音频打开时读取；麦克风静音为独立链路 | 翻译为“尝试将本机听筒或扬声器静音”，避免误解为关闭 SIP 或双向音频；回调与生效时机不动 |
| 待生效设置提示 | `settingsPending` 和 `hasSameSipSettings` | 原 runtime 在通话结束后应用 | 仅替换提示文字 |
| 运行状态：网关、Shizuku、通话音频、Doze、SIP | `readinessDetail`、`shizuku`、`shizukuDetail`、`audioReady`、HealthKeys、`sip.ready`、`sip.detail` | 就绪计算在原 runtime / privileged 中；界面只读 | 标题汉化；所有共用动态数据直接显示原文，不解析、不拼接新状态 |
| 授予 Shizuku 权限 | 原权限状态和 `actionsEnabled` | `shizuku.requestPermission()` | 仅按钮文字汉化，权限状态、请求码、授权流程不动 |
| 重新执行配置 / 重新连接 SIP | 原 `actionsEnabled`、连接状态、`sipConfigured` | `runSetup()` / `restartSip()`；活动通话时原 runtime 延后 | 仅按钮文字汉化 |
| 通话摘要、会话 | `h.calls.summary`、`h.session` | 只读；这些字段也用于其他界面 / 诊断 | 原始值直接显示；仅“无活动会话”等固定占位文案汉化 |
| 当前音频、静音状态、恢复详情、下次通话提示 | `callAudio` 字段、`cur.muteEarpiece` | 只读；`outputMute` 与常量比较的原条件不变 | 只改既有说明文本；backendLabel、详情数据及插值表达式保留 |
| 重试恢复播放声音 | `restorationPending`、`ownsOutputChange`、连接状态、无通话 / 会话条件 | `retryAudioRestoration()` | 仅按钮文字汉化；不能点击的条件保持原样 |
| 蜂窝来电回声测试 | `loopbackTest` | `setLoopbackTest(it)` → `rebuildBridge()`；开启时会接听并回传声音，不桥接 PBX；活动通话时延后 | 标签明确标注“不桥接 PBX”；回调、测试模式和延后逻辑原样保留 |
| 展开 / 收起高级设置 | `advanced`，原 `rememberSaveable` 状态 | 仅控制原折叠区域显示 | 仅文字替换，状态表达式 `advanced = !advanced` 不动 |

## SIP 设置区域

`Field` 的 `label` 只交给 `Text(label)` 显示。输入内容来自单独的 `value`，更新由原 `onValueChange` 处理；标签没有参与字段解析、保存或网络调用。`Choice` 的 `name(opt)` 只交给 `Text` 显示，选中值始终是原 `opt`。

| 显示项目 | 原值 / 保存字段 | 原业务约束 | 本次处理 |
| --- | --- | --- | --- |
| SIP 协议栈 | `SipStack`；保存 `sipStack.id`，即 `builtin` / `pjsua2` | 内置栈只支持 UDP；切换时按原逻辑跟随默认端口 | 只改标题；选项仍用原 `{ it.id }`，不新增中文映射 |
| 传输协议 | `SipTransport`；`sip_transport`：`udp` / `tcp` / `tls` | 原默认端口及 enabled 条件不动 | 只改标题；标识保留原文 |
| SRTP | `SrtpPolicy`；`srtp`：`off` / `optional` / `mandatory` | 仅 pjsua2 + TLS 时可选 | 只改标题；选项保持原标识，避免与保存值混淆 |
| PBX 主机 | `server` → `server` | 保存时原 `trim()` | 仅标签汉化 |
| PBX 端口 | `port` 字符串 → 验证后的 Int → `port` | `1..65535`；`val portValidationError = portError`；保存按钮仍 `enabled = portValidationError == null` | 三处均恢复并保持原版；错误对象和错误字符串不翻译 |
| SIP 域 | `domain` → `domain` | 留空时原 SIP 配置采用 PBX 主机 | 仅标签说明汉化 |
| 分机 / 用户名 | `user` → `username` | 原保存和注册逻辑不动 | 仅标签汉化 |
| 密码 | `password` → Keystore 加密的 `password_sealed` | 密码展示变换、内存保留、密钥和加解密均不变 | 仅“密码”标签汉化，输入内容不处理 |
| 蜂窝来电转接目标 | `target` → `inbound_target` | 留空使用原用户名规则 | 只改显示标签；不改方向、不处理输入 |
| 允许外拨的号码前缀 | `prefixes` → `allowed_prefixes` | 英文逗号分隔；留空不限 | 标签明确写“英文逗号”，不替换用户输入或分隔符 |
| 未保存 / 待生效提示及保存按钮 | `hasUnsavedChanges`、`pending`、通话数量、会话 | `settingsUpdate()` 捕获原表单，再经 `applySettings` 保存或延后；密码和配置不会因文案被重建 | 仅按钮和说明文字汉化；草稿同步、差异比较、快照和应用流程完全保留 |
| 手机不可接管时暂停 SIP 注册 | `holdSipWhenNotReady` → `hold_sip_when_not_ready` | 原 `SipRegistrationGate`、宽限期、活动通话延后均不动 | 仅标题和说明汉化；`${GatewayRuntime.SIP_HOLD_GRACE_MS / 1000}` 原表达式不动 |
| pjsip 调试日志 | `sipDebugLog` → `sip_debug_log` | 原回调控制日志等级；原脱敏逻辑不动 | 仅标签和说明汉化 |

## 高级区域

| 显示项目 | 必须保持的值与业务关系 | 本次处理 |
| --- | --- | --- |
| 通话访问模式 | 列表顺序仍 `AUTO, COMPANION, DIALER`；数值仍 `0,1,2`；原 SDK 条件、`CallAccess.effective` 和配置保存均不动 | 仅已有选项名称和说明字面量汉化 |
| 网关关闭时保留默认电话应用 | `keepDefaultDialer`；原 `dialerControlsEnabled`；`setKeepDefaultDialer(it)` 影响下次关闭时是否尝试恢复 | 仅标签和说明字面量汉化 |
| 蜂窝通话音频方式 | **界面顺序仍 `AUTO, LEGACY, OFFICIAL`；数值对应 `0,2,1`，绝不按中文顺序重新编号。** `OFFICIAL` 仍要求 API 33+ | 仅现有三个名称和说明汉化；参数、列表、判断和回调逐字不变 |
| 诊断信息 | 原 SDK、配置数值、HealthKeys、音频 backend、恢复细节、setup 日志 | 只改外层标题或说明前缀；键和值均原文显示 |
| 日志 | 原 `takeLast(60).reversed().joinToString("\n")` | 完全保留数据和格式，不经过翻译函数 |

## 拨号恢复界面

| 显示内容 | 原业务关系 | 本次处理 |
| --- | --- | --- |
| 通话摘要 / 会话 / 网关提示 | 原 health 和 settings；锁屏可见性仍由 `holdsDialer`、通话数量、incoming 决定 | 摘要原文显示；仅现有固定说明字面量汉化 |
| 开启 / 关闭接管、恢复、系统设置选择、取消、打开配置 | 原 `setTakeoverFromUi(!settings.takeover)`、`::switchDialer`、`::chooseDialerInSettings`、`::cancelDialerChange`、`::openConfiguration` | 仅 Text 文案汉化；所有 enabled 条件、函数引用和解锁要求逐字保留 |
| 默认电话应用角色消息 | `roleMessage` 来源于原 runtime 和系统请求 | 直接显示原文，不翻译，也不新增状态分支 |

## 通知与系统界面

| 通知 / 系统页面 | 原业务关系 | 本次处理 |
| --- | --- | --- |
| 网关前台通知 | `needsForegroundService`、`summary(h)`、foreground flags、通知 ID、channel ID、PendingIntent | **整个 `summary` 函数及 `build(text)` 数据链路恢复原版。** 只汉化通知渠道的可见名称；状态正文保留原文 |
| 蜂窝通话通知 | 原 live calls、RINGING 判断、全屏 intent、类别和锁屏可见性 | 仅渠道名称、标题和静态正文汉化；通知行为不动 |
| 系统设置、角色选择、解锁、Shizuku 授权 | 原 `DialerRoleActivity`、系统 Intent、Shizuku API | 所有代码及参数与基线完全一致，由系统显示自身语言 |

## 验证边界

`tools/verify_display_only_localization.py --self-test` 对照原始提交，逐个核对 `docs/localization-literals.json` 中的 139 处文字替换。将这些文字恢复后，4 个界面 / 通知文件必须与原版逐字一致；所有字符串插值表达式也必须保持一致。134 个其他受保护文件（包括原测试、运行时、配置、协议、音频、清单、资源和构建配置）必须与基线字节相同。禁止新增模块代码文件。

检查器还用故意改坏的示例验证：回调布尔值、校验条件、插值计算式、未审查文案任一改变都会被拒绝。此检查保护的是“本次代码改动的范围”；它不能代替手机上的实际通话、权限、音频和恢复过程验证。

工作流还会运行原有全部单元测试、构建 APK、校验签名，并用同一工具链和调试签名生成基线对照 APK。两个 APK 的业务包名和版本保持原版；文件名用于区分版本，不修改 applicationId 来做双开。

目前没有收到具体复现步骤、设备 / 系统信息或故障日志，因此不能把用户所报异常归因于某段代码，也不能宣称该异常已被真机复现并解决。
