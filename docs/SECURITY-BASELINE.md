# PanelFM 安全基线（2026-10-05）

## 已执行

- 新连接默认不信任自签 TLS 证书；用户显式保存 `trustSelfSigned=true` 的旧连接保持兼容。
- FileProvider 只保留外部存储和 cache 路径，不暴露应用私有 `files/` 根目录（其中可能包含 `keys/`、数据库和配置）。
- 关闭 Android 自动备份：连接配置、数据库和本地索引不随系统备份导出。
- CI release 构建必须存在有效 release keystore；缺少 secrets 时硬失败，禁止生成“debug 签名的 release APK”。
- 本地仍允许 `assembleDebug`，便于个人使用和开发验证。

## 有意保留的兼容边界

- `usesCleartextTraffic=true` 暂时保留，因为 FTP、HTTP WebDAV、部分 S3/MinIO endpoint 仍可能需要明文连接；后续应改为按协议/域名的 Network Security Config，而不是全局关闭。
- “信任自签证书”仍是显式用户开关；下一步应把当前 trust-all 实现替换为按连接保存证书/公钥指纹的信任模型。

## 已完成的依赖修复

- SMBJ 0.13.0 原本解析到 `org.bouncycastle:bcprov-jdk18on:1.75`；已显式约束到 `1.85`，消除当前实际 runtime tree 中的 Bouncy Castle 高危告警。
- `org.tukaani:xz` 已从 `1.10` 升到 `1.12`，归档模块回归通过。
- Dependabot 返回但未出现在当前 runtime tree 的 Netty/jose4j/jdom/httpclient 告警暂不盲目添加，后续以 CI dependency submission 和实际 dependency insight 复核。

## 尚未处理（独立高风险批次）

- R8/minify/shrinkResources：需要为 Media3、反射和各网络协议补 keep 规则后再开启。
- FTP/WebDAV 自定义 TrustManager：不能用 lint suppress 代替安全实现，应增加真实证书/自签证书测试。
- APK/数据库备份迁移策略：关闭 backup 后如需用户迁移，应提供显式、加密、可审计的导出流程。
