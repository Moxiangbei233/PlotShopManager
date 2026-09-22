# PlotShop Manager

A client-only Fabric mod for managing honor-system barrel shops on the Monumenta server. It queries CoreProtect container logs, parses the chat output, and exports per-barrel transaction CSVs.

一个用于 Monumenta 服务器的**纯客户端** Fabric 模组，用来管理「告示牌标价、玩家自觉交易」的无人看管桶商店：自动查询 CoreProtect 的容器记录、解析聊天输出，并按桶导出交易 CSV。

## 为什么是客户端模组

Monumenta 上 CoreProtect 的 Networking API 无法使用（握手失败），因此本模组采用「路线 A」：向服务器发送 `/co lookup` 聊天指令，再解析 CoreProtect 回显到聊天栏的记录。整个过程不需要服务端安装任何东西。

## 功能

- **登记桶**：记录每个桶的世界坐标、商品、买入价、卖出价，持久化到本地 JSON。
- **快速注册**：按下快捷键（默认 `K`）后右键桶，自动读取桶内告示牌（`buy for` / `sell for`）与商品物品，一键登记。
- **一键扫描**：站在公会商店（11x11）或个人商店（7x7）中心，按地皮半径批量查询所有已登记桶的记录，并自动翻页。
- **增量扫描**：记录每个桶上次扫描时间，下次扫描自动从上次时间点续查，节省时间。
- **按桶导出 CSV**：每个桶一个 CSV，另有 `barrels.csv` 汇总登记信息；UTF-8 BOM 编码，Excel 可直接打开。
- **去重**：同一批查询重复解析到的记录只保留一次。

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（Minecraft 1.20.4）与 [Fabric API](https://modrinth.com/mod/fabric-api) 0.91.1+1.20.4。
2. 从 [Releases](../../releases) 下载 `plotshop-manager-1.0.0.jar`，放入 `.minecraft/mods`。

## 使用方法

所有指令均以 `/shop` 开头。先看向桶再执行 `register` / `unregister` / `lookup`。

| 指令 | 说明 |
| --- | --- |
| `/shop register 物品\|买入价\|卖出价` | 登记看向的桶，例如 `/shop register 经验瓶\|10xp\|5xp` |
| `/shop unregister` | 移除看向的桶的登记 |
| `/shop list` | 列出所有已登记桶（按距离排序） |
| `/shop lookup [时间]` | 单独查询看向的桶，默认 `7d`，例如 `/shop lookup 1d` |
| `/shop scan guildplot [时间]` | 扫描公会商店（11x11，半径 8），不带时间则为增量扫描 |
| `/shop scan normalplot [时间]` | 扫描个人商店（7x7，半径 5），不带时间则为增量扫描 |
| `/shop stop` | 停止当前扫描 |
| `/shop status` | 查看已记录条数 / 已登记桶数 / 扫描进度 |
| `/shop last` | 查看最近一条解析到的记录 |
| `/shop export` | 按桶导出 CSV |
| `/shop clear` | 清空内存中的记录 |

**快速注册**：按 `K` 开启（可在「按键设置」中改键），然后右键桶即可自动读取告示牌与内容并登记；再按 `K` 关闭。

## 数据文件

- 登记信息：`.minecraft/config/plotshop-manager/barrels.json`
- 导出记录：`.minecraft/config/plotshop-manager/exports/`
  - `barrel_<坐标>__<物品>.csv` —— 每个桶的交易明细
  - `barrels.csv` —— 登记汇总

CSV 明细列：`timestamp, player, item, material, amount, direction, currency_type, currency_tier, barrel_x, barrel_y, barrel_z, world`。

## 构建

需要 JDK 17+（无需额外安装 Gradle，使用自带 wrapper）：

```bash
# Windows
./gradlew.bat build

# macOS / Linux
./gradlew build
```

生成的模组在 `build/libs/plotshop-manager-1.0.0.jar`。

## 工作原理

1. `/shop scan` 对每个登记桶发送 `co lookup c:X,Y,Z r:1 t:<时间> a:container rows:100`。
2. 通过 `Page X/Y` 页脚识别分页，自动发送 `co l <下一页>` 拉取完整结果。
3. 解析聊天记录，从中提取时间戳、玩家、物品（取 Monumenta 自定义物品悬停名）、数量、方向与货币类型/层级。
4. 导出时按桶聚合写入 CSV。

## 已知限制

- 仅支持 Monumenta（依赖其 CoreProtect 输出格式与物品/货币命名）。
- 外汇桶（告示牌没有 `buy for` / `sell for`）无法用快速注册，需用 `/shop register` 手动登记。
- 增量扫描的时间起点基于**本机时钟**，换电脑或改系统时间后建议用完整时间参数（如 `/shop scan guildplot 30d`）重新校准。

## 许可

[GNU LGPL-3.0-or-later](LICENSE)
