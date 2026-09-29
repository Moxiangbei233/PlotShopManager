# PlotShop Manager

用于 Monumenta 服务器的**纯客户端** Fabric 模组，管理 Plot 桶商店。面向**店主**：登记桶、自动查询 CoreProtect 记录、按桶导出交易 CSV；面向**顾客**：打开桶时自动检测误交易、估算公平价格、记录本地行为日志。顾客功能独立实现，**不依赖 StonkCompanion**。

## 功能

### 店主侧

- **可视化注册**：按快捷键 `G`，填写物品名称、告示牌别名、买/卖价、桶号与商店类型，右侧实时预览告示牌效果；确定后自动登记并**自动填入告示牌**（免手动粘贴），且保存上次输入方便批量建桶。
- **快速注册**：按 `K` 后右键桶，自动读取告示牌与桶内物品一键登记。
- **商店类型**：
  - **专属**：只允许指定物品交易。
  - **rare 通用**：rare 装备与对应 rare 碎片（frag）等价买卖，放入同组任意 rare/frag 都算正确。
  - **互换**：物品 1:1 互换，可带手续费（`free` 或如 `0.5har`）。
- **别名**：告示牌简称与桶内实际物品自动关联（如 `white mat` ↔ `Soul Essence`），可用 `/shop alias` 手动管理。
- **一键扫描**：站在公会商店（11x11）或个人商店（7x7）中心，按地皮半径批量查询所有已登记桶并自动翻页。
- **增量扫描**：记录每个桶上次扫描时间，下次自动从上次时间点续查。
- **按桶导出 CSV**：每个桶一个 CSV，另有 `barrels.csv` 汇总；UTF-8 BOM 编码，Excel 可直接打开。
- **去重**：同一批查询重复解析到的记录只保留一次。

### 顾客侧

- **误交易计算器**：关闭桶时比对物品与货币净变化，依据告示牌价格判断是否正确，错误时提示应补入/退还的货币数量或错误物品。
- **公平价格估算**：根据桶内货币与商品比例，在买/卖价之间插值估算当前公平价。
- **本地行为日志**：每次桶交易写入独立 CSV，记录时间戳、坐标、商品/货币净变化与核销结果。
- **左侧 HUD**：打开桶时显示商品、价格、商店类型与最近交易。

## 使用方法

所有指令以 `/shop` 开头。`register` / `unregister` / `lookup` 需先看向目标桶。

| 指令 | 说明 |
| --- | --- |
| `/shop register 物品\|买\|卖` | 登记看向的桶（自动识别类型） |
| `/shop register rare 物品\|买\|卖` | 登记为 rare 通用商店 |
| `/shop register exclusive 物品\|买\|卖` | 登记为专属商店 |
| `/shop register custom 物品\|手续费` | 登记为互换商店，手续费 `free` 或 `0.5har` |
| `/shop alias 别名\|实际物品名` | 添加/更新别名，如 `white mat\|Soul Essence` |
| `/shop alias list` | 查看所有别名 |
| `/shop unregister` | 移除看向的桶的登记 |
| `/shop list` | 列出所有已登记桶（按距离排序） |
| `/shop lookup [时间]` | 单独查询看向的桶，默认 `7d` |
| `/shop scan guildplot [时间]` | 扫描公会商店（11x11，半径 8），不带时间则为增量扫描 |
| `/shop scan normalplot [时间]` | 扫描个人商店（7x7，半径 5），不带时间则为增量扫描 |
| `/shop stop` | 停止当前扫描 |
| `/shop status` | 查看已记录条数 / 已登记桶数 / 扫描进度 |
| `/shop last` | 查看最近一条解析到的记录 |
| `/shop export` | 按桶导出 CSV |
| `/shop clear` | 清空内存中的记录 |
| `/shop trade` | 查看顾客功能开关状态 |
| `/shop trade mistrade / fairprice / log / hud` | 分别开关误交易检测、公平价、行为日志、HUD |
| `/shop settings` | 打开可视化设置菜单（也可按 `P`） |

**快捷键**：`K` 快速注册，`G` 可视化注册，`P` 设置菜单（均可在「按键设置」中改键）。

**顾客功能**：无需登记，直接打开标有价格的桶即可。打开时自动快照，关闭时在聊天栏提示核销结果与公平价。

## 数据文件

- 登记信息：`.minecraft/config/plotshop-manager/barrels.json`
- 别名：`.minecraft/config/plotshop-manager/aliases.json`
- 顾客功能开关：`.minecraft/config/plotshop-manager/trade.json`
- 上次注册输入：`.minecraft/config/plotshop-manager/register_last.json`
- rare↔frag 对照表（内置）：`rare_frag_map.json`
- 导出记录：`.minecraft/config/plotshop-manager/exports/`
  - `barrel_<坐标>__<物品>.csv` —— 每个桶的交易明细
  - `barrels.csv` —— 登记汇总
- 行为日志：`.minecraft/config/plotshop-manager/trade_logs/x<X>_y<Y>_z<Z>.csv`

## 告示牌格式

```
第1行  物品名（或别名）
第2行  #桶号 ======   （无桶号时写 ======）
第3行  buy for 价格
第4行  sell for 价格
```

互换商店第 3 行写 `exch for 手续费`（如 `exch for 0.5har` 或 `exch for free`）。每行最多 15 字符。

## 构建

需要 JDK 17+：

```bash
# Windows
./gradlew.bat build

# macOS / Linux
./gradlew build
```

生成的模组在 `build/libs/plotshop-manager-1.1.0.jar`。

## 工作原理

1. `/shop scan` 对每个登记桶发送 CoreProtect 查询，自动翻页拉取完整结果。
2. 解析聊天记录，提取时间戳、玩家、物品名、数量、方向与货币类型/层级。
3. 导出时按桶聚合写入 CSV。

## 已知限制

- 仅支持 Monumenta（依赖其 CoreProtect 输出格式与物品/货币命名）。
- 增量扫描的时间起点基于**本机时钟**，换电脑或改系统时间后建议用完整时间参数（如 `/shop scan guildplot 30d`）重新校准。
- 顾客功能通过「打开时快照 → 关闭时比对」实现，无法识别快捷合成（quick-craft）式操作。

## 许可

[GNU LGPL-3.0-or-later](LICENSE)
