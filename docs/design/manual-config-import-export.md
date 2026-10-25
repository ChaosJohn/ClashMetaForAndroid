# 手动配置导入导出设计

> 状态：设计已确认（2026-10-25），待实现。
> 范围：手动新增的 proxies / proxy-groups / rules（`ManualConfig`，独立于订阅存储）。

## 需求背景

手动配置三类条目逐条录入成本高，换机/多设备/分享场景需要整体迁移能力。

## 决策记录

| # | 问题 | 决策 |
|---|------|------|
| Q1 | 文件格式 | YAML（mihomo 片段：`proxies:` / `proxy-groups:` / `rules:` 三段合一） |
| Q2 | 载体 | **二维码 + 文件并存**（B） |
| Q3 | 文件格式细节 | mihomo YAML 片段（A），extras/options 无损往返 |
| Q4 | 导入语义 | **预览对话框**（C）：展示计数与内容摘要，用户选「替换」或「合并」后执行 |
| Q5 | 导出范围 | **三类全量打包**（A），不拆分类别 |
| Q6 | 入口 | **「手动配置」底部弹窗集中入口**（A），三个手动页面零改动 |
| Q7 | 校验 | **预览警告 + 自动清洗**（C）：悬空引用标黄警告，确认后自动剔除不可解析引用，保证导入结果可加载 |

## 格式定义

### 文件导出（YAML）

```yaml
# 由 ClashMetaForAndroid 导出
proxies:
  - {name: 我的节点, type: ss, server: example.com, port: 443, cipher: aes-256-gcm, password: "...", udp: true}
proxy-groups:
  - name: 手动组
    type: select
    proxies: [我的节点, 香港自动]
rules:
  - DOMAIN-SUFFIX,example.org,手动组
```

- 序列化/反序列化在 Go 桥接层实现（`core/src/main/golang/native/`，vendored mihomo 的 YAML 库现成可用）。
- `ManualProxy.extras`（type-specific 字段，如 uuid、ws-opts）与 `ManualProxyGroup.options`（url、interval 等）作为普通映射键展开到 YAML 对应条目，往返无损。
- 文件名建议：`cmfa-manual-YYYYMMDD.yaml`。

### 二维码

- payload：`cmfa://manual?v=1&d=<gzip(URL-safe base64url, 无padding)>`
- `d` 内容为 JSON（`ManualConfig` 同构：`{"proxies":[...],"proxy-groups":[...],"rules":[...]}`，键名与 kotlinx `@SerialName` 一致）。
- 容量预算：QR v40 上限约 2.9KB；gzip+base64url 后约可容纳 20~30 条典型条目。超出时生成二维码入口禁用/提示改用文件导出。
- 入口三种来源：相机实时扫码、相册选图识别、其他 app 内 `cmfa://` 链接点击唤起（manifest 注册 scheme）。
- 导出侧同时支持：显示二维码（对方用相机扫）+ 生成分享链接（`cmfa://` URI，走系统分享面板发微信/TG）。

### 互换性

QR 的 JSON 与 YAML 文件解析后归一到同一中间表示（Go 侧统一转 `ManualConfig` JSON），导入预览/清洗/落库逻辑只有一套。

## 导入流程

1. 解析输入（YAML / cmfa:// URI），失败报格式错误。
2. 解析成功 → 组装 `ManualConfig` 草稿，进入预览对话框：
   - 计数摘要：N 条代理 / M 个组 / K 条规则；
   - 条目列表（名称 + 类型）；
   - **悬空引用标黄**：组成员、规则 target 在「导入包 ∪ 本地手动配置 ∪ 当前订阅全量节点/组」中均解析不到的，逐条列出。
3. 用户选择：
   - **替换**：清空现有三类，写入导入包（清洗后）；
   - **合并**：同名条目（case-insensitive）新数据覆盖旧数据，其余追加；规则追加到现有规则**尾部**（顺序语义）。
4. 落库前自动清洗（无论替换/合并）：
   - 组成员中剔除无法解析的名字（若剔除后组为空，该组保留但预览中已警告）；
   - 规则 target 指向不存在组/节点的，整条剔除；
   - 清洗明细在预览对话框中展示（哪些名字会被剔除）。
5. 写入 `ManualConfig`，触发配置重载。

## 交互流程（入口）

```
Profiles 页 → 「手动配置」底部弹窗
  ├─ 手动代理 / 手动代理组 / 手动规则（现有三项）
  ├─ 导入手动配置
  │    ├─ 扫描二维码（相机）
  │    ├─ 从相册识别（图片二维码解析）
  │    └─ 从文件导入（SAF，.yaml）
  └─ 导出手动配置
       ├─ 导出为文件（SAF createDocument，cmfa-manual-YYYYMMDD.yaml）
       ├─ 显示二维码（大图 + 「保存到相册」）
       └─ 分享链接（cmfa:// URI，系统分享面板）
```

- 三个手动页面（Manual*Activity）不加任何入口（Q6 决策）。
- QR 超容量时「显示二维码/分享链接」置灰 + 文案提示改用文件导出。
- 导出前不弹预览（导出无破坏性）；导入必须过预览。

## 实现拆解（预估）

1. **Go 桥接层**（`core/src/main/golang/native/`）：
   - `ManualConfig` YAML 序列化/解析（含 extras/options 往返）；
   - 引用解析与清洗函数（输入：导入包 + 本地手动配置 + 订阅节点列表，输出：清洗结果 + 警告列表）。
2. **Core Kotlin**：`Clash` 对象暴露 `exportManualYAML()` / `parseManualImport(text)`（返回草稿+警告）。
3. **Service**：`IClashManager` 增加 AIDL 方法；落库仍走现有 `patchManualConfig`。
4. **App/Design**：
   - `DialogManualConfigMenu` 布局加两项；
   - 导入预览对话框（复用 AppBottomSheetDialog 风格）；
   - 二维码生成/扫描（引入 `zxing/core` + `zxing-android-embedded`，仅扫码生成用）；
   - 相册识码（`ContentResolver` 解码 bitmap → zxing）；
   - SAF 文件导入导出（复用 `service/document/Picker.kt` 模式）。
5. **Manifest**：注册 `cmfa://` scheme 的 activity-alias（深链唤起导入预览）。

## 边界与注意

- 订阅引用合法但订阅不同的设备上导入 → 走 C 的自动清洗，不误拒。
- 空配置导出：允许（生成只有注释的 YAML / 最小 URI），预览提示空包。
- 替换前不自动备份（预览已明确展示替换语义；如需回退，用户可先导出当前配置）。
- 密码等敏感字段在 YAML/QR 中明文存在，分享 QR 前提示「包含节点凭据」。
- 规则顺序语义：合并只追加尾部，绝不重排。
