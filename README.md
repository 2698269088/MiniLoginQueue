# MiniLoginQueue

轻量版多服务器登录排队插件。部署在 BungeeCord / Velocity 代理下的登录排队服中，为每个目标服务器维护独立排队队列，容量允许时按优先级自动放行玩家进入目标服务器。

仅依赖代理自带的 BungeeCord 原生消息通道与 Minecraft 服务器列表协议（MSLP）即可工作，**无需任何代理端扩展插件**（如 BC/VC 同步插件），亦无需数据库。

## 功能特性

- **独立多服务器队列**：每个目标服务器拥有独立队列，容量、放行节奏互不影响
- **优先级排队**：支持按权限节点 / 玩家名 / UUID / 正则 / 权限组配置优先级与权重
- **双源在线检测**：
  - MSLP 直连探测（优先）：通过 `ServerIP` 通道解析子服地址后直连探测，获得真实在线人数、最大容量、版本与延迟，能准确识别"代理已配置但实际宕机"的服务器
  - BC `PlayerCount` 通道（兜底）：探测不可用时降级使用
- **自动放行**：服务器在线且负载低于阈值时按序放行；支持每轮放行数量上限（`release-limit`）控制节奏
- **服务器选择菜单**：多服务器时玩家点击"加入游戏"按钮弹出 GUI 选择目标服务器；支持服务器显示名（`display-name`）
- **加入游戏按钮**：手动入队模式向玩家发放可点击的物品按钮
- **等待区限制**：限制排队玩家的移动、方块交互/放置/破坏、攻击实体、丢物品、操作容器等（管理员可绕过）
- **排队区域管理**：
  - 活动范围限制：以两个对角坐标圈定长方体区域，排队玩家越界自动拉回（无形墙）
  - 区域保护：区域内禁止任何方式破坏/放置方块（玩家、爆炸、火焰、液体流动、末影人搬运等），排队玩家免疫伤害并禁止 PVP
- **AuthMe 可选集成**：服务器装有 AuthMe 时，未登录玩家禁止入队与放行；未装则自动跳过，零配置
- **跨服管理命令**：管理员可通过原生通道实现 `/mlq send`（跨服转移玩家）、`where`（查询所在服务器）、`ip`（查询真实 IP）
- **多语言**：简体中文 / 繁体中文 / English（语言文件缺失键自动补全）
- **轻量零存储**：无数据库、无配置文件生成的数据文件，排队状态纯内存管理

## 环境要求

| 项目 | 要求 |
|---|---|
| Java | 8+ |
| 服务端 | Spigot / Paper 1.13+（`spigot.yml` 需开启 `bungeecord: true`） |
| 代理 | BungeeCord / Waterfall / Velocity（Velocity 需开启 `bungee-plugin-message-channel`） |
| 可选 | AuthMe（5.6+，用于登录验证） |

> MSLP 直连探测要求登录服与目标服务器端口 TCP 互通；若网络隔离，可将 `queue.mslp-enabled` 设为 `false` 回退纯 BC 通道模式。

## 安装

1. 构建插件：`mvn package`，产物位于 `target/MiniLoginQueue-*.jar`
2. 将 jar 放入登录排队服的 `plugins` 目录并重启服务器（或使用 PlugMan 等热加载）
3. 编辑 `plugins/MiniLoginQueue/config.yml`：
   - 将 `servers` 中的 `name` 改为目标服务器在代理中的名称（必须与代理配置一致）
   - 可选配置各服务器的 `display-name`（菜单显示名）
4. 执行 `/mlq reload` 生效

## 配置说明

配置文件为 `config.yml`，修改后执行 `/mlq reload` 即可生效（区域配置在重载时一并刷新）。

| 配置段 | 说明 |
|---|---|
| `servers` | 目标服务器列表。`name` 必须与代理端服务器名一致；`display-name` 为菜单显示名（支持颜色代码，留空显示内部名） |
| `server-selector` | 选择菜单开关、标题与在线/离线/未知状态物品材质 |
| `queue` | 排队核心：容量回退值、放行阈值、放行节奏、刷新周期、自动入队、锁定时间、优先级、活动范围/区域保护、出生点、游戏模式、"加入游戏"按钮等 |
| `queue.mslp-enabled` | MSLP 直连探测总开关（默认开启） |
| `queue.range` | 排队区域：`pos1` / `pos2` 两个对角坐标构成的长方体（世界取 `queue.spawn.world`），供活动范围限制与区域保护共用 |
| `queue.auto-queue` | `true` 时玩家加入即自动排队（多服务器自动弹选择菜单）；`false` 时需点击"加入游戏"按钮或使用 `/join` |
| `auth` | AuthMe 登录验证开关（默认开启，未装 AuthMe 自动跳过） |
| `restrictions` | 等待区行为限制总开关及各分项 |

完整键含义见 `config.yml` 内注释。

## 命令

### 玩家命令

| 命令 | 说明 |
|---|---|
| `/join [服务器]` | 加入排队队列（多服务器且启用菜单时弹出选择菜单） |
| `/mlq join [服务器]` | 同 `/join` |
| `/mlq leave` | 退出当前排队队列 |
| `/mlq menu` | 打开服务器选择菜单 |
| `/mlq help` | 查看帮助 |

### 管理命令（均需对应权限，默认授予 OP）

| 命令 | 说明 | 权限 |
|---|---|---|
| `/mlq skip <玩家> [服务器]` | 直接放行玩家（跳过排队） | `miniloginqueue.admin.skip` |
| `/mlq send <玩家> <服务器>` | 将任意玩家直接转移至指定服务器（可跨服） | `miniloginqueue.admin.send` |
| `/mlq where <玩家>` | 查询玩家当前所在服务器 | `miniloginqueue.admin.where` |
| `/mlq ip <玩家>` | 查询玩家的真实 IP | `miniloginqueue.admin.ip` |
| `/mlq list [服务器]` | 查看排队列表 | `miniloginqueue.admin.list` |
| `/mlq status` | 查看服务器状态与队列信息 | `miniloginqueue.admin.status` |
| `/mlq refresh` | 手动刷新服务器状态 | `miniloginqueue.admin.status` |
| `/mlq pause` | 暂停队列放行 | `miniloginqueue.admin.pause` |
| `/mlq resume` | 恢复队列放行 | `miniloginqueue.admin.pause` |
| `/mlq reload` | 重新加载配置 | `miniloginqueue.admin.reload` |

> `/mlq where`、`/mlq ip` 等基于代理原生通道的查询为异步操作，需登录服至少一名在线玩家作为消息载体；目标玩家不在线时查询会超时并给出提示。

## 权限

| 权限 | 说明 | 默认 |
|---|---|---|
| `miniloginqueue.admin.skip` | 允许直接放行玩家 | op |
| `miniloginqueue.admin.send` | 允许跨服转移任意玩家 | op |
| `miniloginqueue.admin.where` | 允许查询玩家所在服务器 | op |
| `miniloginqueue.admin.ip` | 允许查询玩家真实 IP | op |
| `miniloginqueue.admin.list` | 允许查看排队列表 | op |
| `miniloginqueue.admin.status` | 允许查看状态与手动刷新 | op |
| `miniloginqueue.admin.pause` | 允许暂停/恢复队列 | op |
| `miniloginqueue.admin.reload` | 允许重载配置 | op |
| `miniloginqueue.admin.bypass` | 不受等待区限制（可自由移动/交互/出入排队区域，不被强制改游戏模式） | op |

## 优先级规则

`queue.priority` 支持以下匹配方式（越靠前默认权重越高，也可显式指定权重）：

```
permission:权限节点[:权重]   拥有指定权限的玩家
name:玩家名[:权重]           指定玩家名（精确匹配）
uuid:玩家UUID[:权重]         指定玩家 UUID
regex:正则表达式[:权重]       玩家名匹配正则
group:权限组名[:权重]         拥有指定权限组的玩家（按 group.组名 权限检测）
```

## 构建

```bash
mvn package
```

首次构建需联网拉取依赖（含 AuthMe 可选编译依赖，来自 codemc 仓库）。

## 语言

语言文件位于 `plugins/MiniLoginQueue/lang/`，支持 `zh_CN`、`zh_TW`、`en_US`。修改 `config.yml` 的 `language` 字段切换；旧语言文件缺少新增键时会自动补全默认文案。
