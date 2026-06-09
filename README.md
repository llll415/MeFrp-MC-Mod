# MeFrp-MC-Mod

<p align="center">
  <a href="https://www.mefrp.com">
    <img src="https://www.mefrp.com/favicon.svg" alt="MeFrp Logo" width="96" height="96">
  </a>
</p>

<p align="center">
  一个面向 Minecraft 局域网联机的 MeFrp 辅助 Mod
</p>

<p align="center">
  <a href="https://www.mefrp.com">MeFrp 官网</a>
</p>

## 介绍

MeFrp-MC-Mod 可以在游戏内帮助玩家完成 MeFrp 联机流程：检测局域网端口、创建或复用 TCP 隧道、启动内置官方 `mefrpc`，并在聊天栏输出可点击复制的连接地址

适合没有公网 IP、想把单人世界局域网开放给异地朋友加入的玩家

主要功能：

- 自动检测 Minecraft 局域网端口
- 自动创建或复用固定名称隧道 `Mefrp-MC-MOD`
- 内置官方 `mefrpc`，运行时自动解压并启动
- 聊天栏输出连接地址，点击即可复制
- 支持节点选择，显示节点编号、地区、带宽、负载、VIP 标识
- 进入世界后显示账号信息、流量、限速、签到状态、隧道数量
- 支持 `/mefrp online on|off` 切换局域网正版验证
- 兼容 `LanServerProperties` 和 `mcwifipnp`；检测到这些 Mod 时不接管正版验证开关

## 安装

1. 安装对应 Minecraft 版本的 Fabric / Forge / NeoForge
2. Fabric 版本需要安装对应版本的 Fabric API
3. 从发布页或 MC 百科下载对应 Minecraft 版本和加载器的 MeFrp-MC-Mod
4. 放入游戏目录的 `mods` 文件夹
5. 启动游戏

## 首次使用

1. 前往 [MeFrp 官网](https://www.mefrp.com) 用户中心获取访问密钥
2. 进入游戏后输入：

```text
/mefrp token <访问密钥>
```

3. 打开单人世界暂停菜单，点击“对局域网开放”
4. 输入：

```text
/mefrp start
```

5. 聊天栏会输出连接地址，点击即可复制，把地址发给朋友即可加入

如果自动检测不到端口，可以手动指定：

```text
/mefrp start <端口>
```

## 命令

| 命令 | 说明 |
| --- | --- |
| `/mefrp help` | 查看帮助 |
| `/mefrp token <访问密钥>` | 保存 MeFrp API 访问密钥 |
| `/mefrp start [端口]` | 启动隧道；不填端口时自动检测 |
| `/mefrp stop` | 停止本地 `mefrpc` 并等待远端隧道离线 |
| `/mefrp status` | 查看当前隧道状态、mefrpc 状态、PID 和内存占用 |
| `/mefrp userinfo` | 查看账号、流量、限速、签到状态 |
| `/mefrp sign` | 提示前往 MeFrp 官网完成签到 |
| `/mefrp node` | 选择节点并启动/切换隧道 |
| `/mefrp select <编号>` | 选择节点列表中的指定节点 |
| `/mefrp online on` | 开启局域网正版验证 |
| `/mefrp online off` | 关闭局域网正版验证 |

说明：

- `/mefrp start` 会自动创建或复用名为 `Mefrp-MC-MOD` 的 TCP 隧道
- `/mefrp sign` 不会直接调用签到接口；MeFrp 签到需要在官网完成人机验证
- 如果安装了 `LanServerProperties` 或 `mcwifipnp`，请在这些 Mod 的界面里调整正版验证；MeFrp 仍然负责端口检测和隧道启动

## 账号信息

进入世界后，如果已经绑定访问密钥，Mod 会显示账号信息：

```text
[MeFrp] MeFrp-MC-Mod
[MeFrp] 账号: hoshino_
[MeFrp] 身份: 正式用户
[MeFrp] 账号状态: 正常
[MeFrp] 实名状态: 已实名
[MeFrp] 今日签到: 未签到 [点击签到]
[MeFrp] 剩余流量: 69.0 GB
[MeFrp] 限速: 上行 50 Mbps / 下行 50 Mbps
[MeFrp] 隧道数量: 1 / 10
```

如果没有绑定访问密钥，会提示前往 MeFrp 官网用户中心获取访问密钥，并使用 `/mefrp token <访问密钥>` 完成绑定

## 节点选择

输入：

```text
/mefrp node
```

请先打开局域网联机节点列表会显示节点编号、地区、带宽和负载：

```text
1. #12 上海节点 | 中国大陆 | 200Mbps | 负载 18% [点击选择]
2. #23 广州高速 [VIP] | 中国大陆 | 500Mbps | 负载 42% [点击选择]
3. #31 某节点 [过载] | 中国大陆 | 200Mbps | 负载 95% [点击选择]
```

规则：

- VIP 专属节点会显示 `[VIP]`
- 负载大于 `90%` 的节点会显示 `[过载]`，不可选择
- 普通用户不能选择 VIP 专属节点

## 配置文件

用户配置位置：

```text
config/mefrp.json
```

运行时 `mefrpc` 会解压到：

```text
config/mefrp/bin/
```

请不要把 `config/mefrp.json` 分享给别人，里面保存了你的 MeFrp API 访问密钥

## 支持版本及其测试情况

| Minecraft | Fabric | NeoForge | Forge |
| --- | --- | --- | --- |
| 26.1.x | 支持&测试通过 | 支持&测试通过 | 不支持 |
| 1.21.1 | 支持&测试通过 | 支持&测试通过 | 不支持 |
| 1.20.1 | 支持&测试通过 | 支持&测试通过 | 支持&测试通过 |
| 1.16.5 | 支持&测试通过 | - | 支持&测试通过 |
| 1.12.2 | - | - | 支持&测试通过 |
| 1.7.10 | 待定 | 待定 | 待定 |

以上测试均在 `Windows amd64` 环境测试通过`Linux`、`macOS` 未测试不做保证
纯服务端环境大部分功能不可用，请等待后续修复
经测试youer c1f4731a/neoforge 21.1.223均出现问题，帮助菜单不显示，启动隧道信息不显示.....

## 构建

以下命令用于自行构建

目标 Java 版本：

| 目标 | Java |
| --- | --- |
| 26.1.x | Java 25 |
| 1.21.1 | Java 21 |
| 1.20.1 | Java 17 |
| 1.16.5 | Java 8 |
| 1.12.2 | Java 8 |

根项目已配置 Gradle Java Toolchains开发者只需要安装一个能启动 Gradle 的 JDK；构建现代版本时，Gradle 会按需自动解析 Java 17 / 21 / 25 toolchain若自动下载失败，请自行安装对应 JDK，并在本机 Gradle 配置中设置 `org.gradle.java.installations.paths`

现代版本使用根项目构建：

```bash
./gradlew build
```

Fabric 1.16.5 独立构建：

```bash
cd fabric-1.16.5
./gradlew build
```

Forge 1.16.5 独立构建：

```bash
cd forge-1.16.5
./gradlew build
```

Forge 1.12.2 独立构建：

```bash
cd forge-1.12.2
./gradlew build
```

1.16.5 / 1.12.2 为独立老版本项目，使用各自目录内的 Gradle Wrapper 构建；这些项目不走根项目 toolchain 配置，请按对应老版本环境自行准备 JDKFabric 1.16.5 和 Forge 1.12.2 建议使用 JDK 8 构建

## 项目结构

```text
common              现代版本公共逻辑
fabric              26.1.x Fabric
neoforge            26.1.x NeoForge
fabric-1.21.1       1.21.1 Fabric
neoforge-1.21.1     1.21.1 NeoForge
fabric-1.20.1       1.20.1 Fabric
neoforge-1.20.1     1.20.1 NeoForge
forge-1.20.1        1.20.1 Forge
common-legacy       低版本 Java 8 公共逻辑
fabric-1.16.5       1.16.5 Fabric 独立项目
forge-1.16.5        1.16.5 Forge 独立项目
forge-1.12.2        1.12.2 Forge 独立项目
```

## 注意事项

- 使用本 Mod 需要可用的 MeFrp 账号和访问密钥
- 节点可用性、流量、限速、VIP 权限和签到状态以 MeFrp 官网/API 返回为准
- Mod 启动 `mefrpc` 时使用的是 MeFrp API 返回的 `frpToken`，不是用户输入的访问密钥
- mefrpc 日志会输出进 Minecraft 日志，便于排查问题

## 开源许可

本项目代码使用 MIT License 开源

MeFrp、MeFrp 官网、MeFrp API 服务和 `mefrpc` 的相关权利归其各自权利方所有本项目不是 MeFrp 官方项目；内置 `mefrpc` 仅用于配合本 Mod 启动穿透客户端
