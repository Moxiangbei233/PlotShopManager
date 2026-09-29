# PlotShop Manager

一个用于 Monumenta 服务器的**纯客户端** Fabric 模组，用来管理的Plot桶商店。面向**店主**：自动查询 CoreProtect 的容器记录、解析聊天输出，并按桶导出交易 CSV；面向**顾客**：打开桶时自动检测误交易、估算公平价格，并记录本地交易行为日志。顾客功能独立实现，**不依赖 StonkCompanion**。


## 功能

### 店主侧

- **登记桶**：记录每个桶的世界坐标、商品、买入价、卖出价，持久化到本地 JSON。
- **快速注册**：按下快捷键（默认 `K`）后右键桶，自动读取桶内告示牌（`buy for` / `sell for`）与商品物品，一键登记。
- **一键扫描**：站在公会商店（11x11）或个人商店（7x7）中心，按地皮半径批量查询所有已登记桶的记录，并自动翻页。
- **增量扫描**：记录每个桶上次扫描时间，下次扫描自动从上次时间点续查，节省时间。
- **按桶导出 CSV**：每个桶一个 CSV，另有 `barrels.csv` 汇总登记信息；UTF-8 BOM 编码，Excel 可直接打开。
- **去重**：同一批查询重复解析到的记录只保留一次。

### 顾客侧

- **误交易计算器**：打开桶后自动对桶内容做快照，关闭时比对物品与货币净变化，依据告示牌价格判断交易是否正确，错误时给出应补入/应退还的货币数量。
- **公平价格估算**：根据桶内货币与商品比例，在买入价与卖出价之间插值估算当前公平价，并提示该看更低或更高的桶。
- **本地行为日志**：每次桶交易（含误交易）写入独立 CSV，记录时间戳、桶坐标、商品/货币净变化与核销结果。


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
| `/shop trade` | 查看顾客功能开关状态（误交易检测 / 公平价 / 行为日志） |
| `/shop trade mistrade` | 开关误交易检测 |
| `/shop trade fairprice` | 开关公平价格估算 |
| `/shop trade log` | 开关本地行为日志 |

**快速注册**：按 `K` 开启（可在「按键设置」中改键），然后右键桶即可自动读取告示牌与内容并登记；再按 `K` 关闭。

**顾客功能**：无需登记，直接右键打开任意标有 `buy for` / `sell for` 告示牌的桶即可。打开时自动快照桶内容，关闭时比对变化并在聊天栏提示核销结果、公平价；默认三项功能全部开启，可用 `/shop trade ...` 按需开关。

## 数据文件

- 登记信息：`.minecraft/config/plotshop-manager/barrels.json`
- 顾客功能开关：`.minecraft/config/plotshop-manager/trade.json`
- 导出记录：`.minecraft/config/plotshop-manager/exports/`
  - `barrel_<坐标>__<物品>.csv` —— 每个桶的交易明细
  - `barrels.csv` —— 登记汇总
- 行为日志：`.minecraft/config/plotshop-manager/trade_logs/`
  - `x<X>_y<Y>_z<Z>.csv` —— 每个桶的本地交易行为记录

CSV 明细列：`timestamp, player, item, material, amount, direction, currency_type, currency_tier, barrel_x, barrel_y, barrel_z, world`。

## 构建

需要 JDK 17+（无需额外安装 Gradle，使用自带 wrapper）：

```bash
# Windows
./gradlew.bat build

# macOS / Linux
./gradlew build
```

生成的模组在 `build/libs/plotshop-manager-1.1.0.jar`。

## 工作原理

1. `/shop scan` 对每个登记桶发送 `co lookup c:X,Y,Z r:1 t:<时间> a:container rows:100`。
2. 通过 `Page X/Y` 页脚识别分页，自动发送 `co l <下一页>` 拉取完整结果。
3. 解析聊天记录，从中提取时间戳、玩家、物品（取 Monumenta 自定义物品悬停名）、数量、方向与货币类型/层级。
4. 导出时按桶聚合写入 CSV。

## 已知限制

- 仅支持 Monumenta（依赖其 CoreProtect 输出格式与物品/货币命名）。
- 告示牌没有 `buy for` / `sell for` 的桶无法用快速注册，需用 `/shop register` 手动登记。
- 增量扫描的时间起点基于**本机时钟**，换电脑或改系统时间后建议用完整时间参数（如 `/shop scan guildplot 30d`）重新校准。
- 顾客功能通过「打开时快照 → 关闭时比对」实现，无法识别快捷合成（quick-craft）式操作；如需该场景请勿仅依赖本模组核销。

## 许可

[GNU LGPL-3.0-or-later](LICENSE)
