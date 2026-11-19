<div align="center">

# 分期管家

**把账单截图丢进去，分期台账自动建好。**

一个只存本地的 Android 分期还款记录工具 —— 截图交给视觉大模型抄成结构化台账，人工确认后入库。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://developer.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

</div>

---

## 特性

| 能力 | 说明 |
|------|------|
| **截图识别** | 一次可选多张截图（如「借款详情」+「还款计划」），模型自动拼成一条记录。针对真实账单内置大量判据：区分「已还」与「已入账」、现金借款无账单日、逐期计划进 `plan` 而非备注、机构别名归一、疑似重复合并等 |
| **人工确认关口** | 识别结果先进草稿，不直接入库。校验不通过、字段缺失、疑似重复都会摊成可一键处置的提示条，确认后才落账 |
| **还款提醒** | 可把每期还款日写进系统日历，走日历自身闹钟，不依赖 App 后台存活 |
| **本机台账** | 总览 / 日历 / 详情多屏，数据存在应用私有目录 `files/loans.json`，支持导出与恢复 |
| **免 Key 可用** | 可复制内置提示词，连截图一起发给任意视觉模型，把回答的 JSON 粘回来导入 |

---

## 快速开始

### 编译

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

需先安装 Android SDK，并在项目根目录建 `local.properties`：

```properties
sdk.dir=/你的/Android/Sdk 路径
```

### 运行测试

```bash
./gradlew test          # 272 个单元测试
```

### 配置识别服务

App 里「我的 → 识别服务」有三个字段：

| 字段 | 默认值 |
|------|--------|
| 接口地址 | `https://api-inference.modelscope.cn/v1` |
| 模型 | `Qwen/Qwen3.5-35B-A3B` |
| API Key | 留空（需自行申请） |

默认走 [魔搭 ModelScope API-Inference](https://modelscope.cn/) 免费通道：注册账号 → 绑定阿里云并实名 → 「我的 → 访问令牌」创建 Access Token 填入即可。免费额度为每日 2000 次调用，个人自用绰绰有余。也可换成任意 OpenAI 兼容的视觉服务，改地址和模型名即可。

填完点「检测连接」可一键验证 Key 与模型是否可用。对 Qwen3.5 / 3.8 系列，App 会自动关闭思考模式（`enable_thinking=false`），避免思考过程吃掉 token 上限、拖慢识别。

---

## 技术栈

<div>

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **数据**：Kotlinx Serialization、Coroutines
- **网络**：视觉识别走 OpenAI 兼容的 `/chat/completions` 接口，`HttpURLConnection` 实现，无第三方网络 SDK
- **兼容**：最低 Android 8.0（API 26），target SDK 35

</div>

### 项目结构

```text
app/src/main/java/com/cyj265/installments/
├── data/       # 存储、视觉识别、平台字典、金额与日期工具
├── domain/     # 对账合并、身份判定、校验、组装（纯逻辑，可测）
└── ui/         # Compose 界面与 ViewModel
app/src/test/   # 272 个单元测试，重点覆盖领域逻辑与解析
```

---

## 隐私与安全

> 这个 App 处理的是个人财务信息，隐私是设计前提，不是事后补丁。

### 数据存到哪里

所有分期记录只保存在**应用私有目录**的 `files/loans.json`，不上传任何服务器，开发者无法访问。应用没有账号体系，也没有云端同步。

### 网络请求

唯一的网络通信是识别截图时，把图片发给你**自己配置**的模型服务接口（默认魔搭 API-Inference）。图片只发给这个你自己选的端点，App 不内置任何第三方 SDK，也不向任何地址上报使用数据、设备信息或账目内容。不配 Key 则不产生任何网络请求。

### 权限

只申请三项权限：`INTERNET`（识别用）、`READ_CALENDAR` / `WRITE_CALENDAR`（写还款提醒用，仅在开启该功能时动态申请）。**不申请相册权限** —— 选图走 Android 系统 Photo Picker，App 无需读取整个相册。

### API Key 的处理

- Key 只存在应用私有目录的 SharedPreferences（`fq_settings.xml`），其他应用读不到。
- 不进 Git，不进 App 自己的导出备份（导出只有 `loans.json`，不含 Key）。
- 系统级备份也已排除该配置文件（Android 12+ 的 `data_extraction_rules` 与 Android 11- 的 `backup_rules` 均排除 `fq_settings.xml`），换机或云备份不会把它带出本机。

### 分发提醒

当前 release 版复用 debug 签名，仅自用分发；正式上架请替换为正式签名配置。**若要公开发布 APK 给他人，务必先在「我的」页清空 Key**，让对方填自己的 —— Key 不会被提交进本仓库，但装在 APK 所在设备上的配置需要手动清除。

---

## 已知限制

- 识别依赖云端模型，离线不可用（不配 Key 时可用手动粘 JSON 的方式）。
- 「还款计划」那种按月列出多笔借款的汇总页读不出单笔，需要截逐笔列出的「应还款详情」。
- 免费通道的模型会动态调整，若某模型下架或限流，App 会给出明确中文提示，换模型即可。

---

## License

本项目采用 [MIT License](LICENSE)，你可以自由使用、修改、分发，包括商用，只需保留原版权声明。

识别服务的 API Key 由使用者自行申请和配置，本项目不提供也不内置任何 Key。

---

<div align="center">

Made with care · Kotlin + Compose

</div>
