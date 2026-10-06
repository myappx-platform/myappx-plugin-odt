# MyAppx Plugin ODT

**语言 / Language:** 中文 | [English](README.en.md)

[![License](https://img.shields.io/badge/license-GPL--2.0-brightgreen.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)

**MyAppx Plugin : ObjectData Tool (ODT)** 是面向 [iDempiere](https://www.idempiere.org/) 的 OSGi 插件，用结构化「对象数据」管理 Application Dictionary（AD）元数据。支持打包、版本化、导入/导出、安装，并可选与 iDempiere 2Pack 协同。

其它 MyAppx 插件可继承 `ODT2PackActivator`，在 Bundle 启动时自动安装自身的 ODT 包与 `ODT2Pack-*.zip`。

| 项目 | 说明 |
|------|------|
| Aggregator | `org.idempiere.myappx.plugin.odt.main` |
| Bundle Symbolic Name | `org.idempiere.myappx.plugin.odt`（`singleton:=true`） |
| Bundle Version | `14.0.0.qualifier` |
| P2 仓库 | `org.idempiere.myappx.plugin.odt.p2`（category `myappx.odt.extensions`） |
| 默认 Activator | `Activator`（仅 ODT，不执行 2Pack） |
| 完整部署 Activator | `ODT2PackActivator`（ODT + `2Pack_*.zip` + `ODT2Pack-*.zip`） |
| Java | 17+（`JavaSE-17`） |
| iDempiere | 14 |
| 构建 | Maven / Tycho 4.x（`eclipse-plugin` + `eclipse-repository`） |
| Parent | `idempiere.extension.parent` |
| 访问级别 | System（AccessLevel = 4） |
| Entity Type（扩展仓库） | `MYAPPX.ODT` |
| License | [GNU General Public License Version 2](LICENSE.md) |
| Vendor | Ken Longnan \<ken.longnan@gmail.com\> |

---

## 目录

1. [功能概述](#1-功能概述)
2. [架构](#2-架构)
3. [构建与依赖](#3-构建与依赖)
4. [项目结构](#4-项目结构)
5. [数据模型](#5-数据模型)
6. [ODTPackage.xml 格式](#6-odtpackagexml-格式)
7. [安装流程](#7-安装流程)
8. [安装后 DDL 处理](#8-安装后-ddl-处理)
9. [后台进程（Process）](#9-后台进程process)
10. [核心服务与工具类](#10-核心服务与工具类)
11. [附件处理](#11-附件处理)
12. [Version Refresh](#12-version-refresh)
13. [2Pack 与 ODT2Pack zip](#13-2pack-与-odt2pack-zip)
14. [典型工作流](#14-典型工作流)
15. [测试与示例](#15-测试与示例)
16. [P2 部署](#16-p2-部署)
17. [日志](#17-日志)
18. [故障排查](#18-故障排查)
19. [已知限制](#19-已知限制)
20. [相关系统配置](#20-相关系统配置)
21. [许可证](#21-许可证)

---

## 1. 功能概述

### 1.1 ODT 能做什么

ODT 将 iDempiere AD 对象（窗口、表、字段、菜单、索引、流程附件等）序列化为 XML，便于：

- 在开发 / 测试 / 生产环境间迁移元数据
- 随插件 Bundle 携带 `META-INF/ODTPackage.xml`（或 `ODTPackage*.xml`），在激活时自动安装
- 在系统内通过 **包（Package）+ 版本（Version）** 维护、刷新、导出、安装

每条 **ObjectData（OD）** 对应一张 AD 表的一条记录，以 **UUID** 定位；每条 **ObjectDataLine（ODL）** 对应一个字段的新值（`NewValue` / `NewID` / `NewUUID`）。

### 1.2 与 2Pack 的关系

| 机制 | 用途 |
|------|------|
| **ODT** | 细粒度 AD 对象迁移；适合插件自带字典、可按 EntityType 做版本 diff |
| **2Pack** | iDempiere 标准 Pack In/Out；适合完整字典快照与租户级数据包 |

本插件同时支持两条路径：

- **`Activator`**（当前 `Bundle-Activator`）：只读取并安装 **单个** `META-INF/ODTPackage.xml`，`packIn()` 为空，不执行 2Pack
- **`ODT2PackActivator`**：Pre-Install → ODT（`ODTPackage*.xml`）→ `2Pack_*.zip` → `ODT2Pack-*.zip` 完整流水线

其它插件若要在启动时同时 Pack In 2Pack / `ODT2Pack-*.zip`，应 **继承 `ODT2PackActivator`**（本 Bundle 已 `Export-Package`）。

### 1.3 自举安装（Bootstrap）

ODT 插件 **首次** 安装时，`KS_ODTPackage` 等表可能尚未写入 AD 字典。此时：

1. `ODTVersionCheck.isOdtRuntimeAvailable()` 检测到 `KS_ODTPackage` 未注册，跳过版本 DB 查询，允许安装
2. `ODTInstallEngine.installFromVersionXml()` 从 XML 写入 AD 元数据（含物理表 DDL 后处理）
3. `CacheMgt.get().reset()` 刷新 AD 缓存
4. `MKSODTPackage.importFromXmlNode()` 写入 `KS_ODT*` 业务表

后续 Bundle 升级再按包 **Name** + **VersionNo** 做版本门控。`Activator` 只处理 **单个** `META-INF/ODTPackage.xml`；文件不存在则 `install()` 直接返回。

---

## 2. 架构

### 2.1 模块分层

```text
┌─────────────────────────────────────────────────────────────────┐
│  入口层                                                          │
│  Activator / ODT2PackActivator / SvrProcess (8 个)              │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  服务层 (service/)                                               │
│  ODTInstallEngine — 统一安装流水线                                │
│  ODTVersionCheck  — Bundle 版本门控 / Bootstrap 检测              │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  领域层 (model/)                                                 │
│  MKSODTPackage / MKSODTVersion / MKSODTObjectData / Line        │
│  ODTPOHelper / ODTPOBuilder — PO 查找、赋值                       │
│  ODTDdlService / ODTIndexService / ODTPostInstallService — DDL  │
│  ODTLookupHelper / ODTQueryHelper / ODTXmlHelper — 导出与 XML    │
│  AttachmentODTUtil — 流程附件                                     │
└─────────────────────────────────────────────────────────────────┘
```

OSGi Declarative Services：

| 组件 | 接口 | ranking |
|------|------|---------|
| `ODTModelFactoryImpl` | `IModelFactory` | 5 |
| `ODTProcessFactoryImpl` | `IProcessFactory` | 5 |

二者均为 `AnnotationBased*` 工厂，扫描 `org.idempiere.myappx.plugin.odt.model` / `.process` 包。`Bundle-ActivationPolicy: lazy`。

### 2.2 安装数据流

```text
                    ┌──────────────────┐
                    │  安装入口         │
                    │  Bundle / Process │
                    └────────┬─────────┘
                             │
              ┌──────────────▼──────────────┐
              │  ODTVersionCheck (Bundle)   │
              │  bootstrap / 版本门控        │
              └──────────────┬──────────────┘
                             │
              ┌──────────────▼──────────────┐
              │  ODTInstallEngine           │
              │  AD Object 循环              │
              │  → Attachment 循环           │
              └──────────────┬──────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
   ODTPOHelper         ODTPOBuilder        SQL_Apply
   generatePO          buildPO             (可选)
         │                   │
         └─────────┬─────────┘
                   ▼
            po.saveEx()
            persistEmptyStrings (PostgreSQL)
            queueColumnSync (AD_Column)
                   │
                   ▼
         ODTPostInstallService
         列同步 → 索引 → 外键 → 序列 → 角色
                   │
                   ▼
         importFromXmlNode (仅 Bundle 路径)
```

### 2.3 Version Refresh 数据流

```text
VersionRefreshProcess
        │
        ▼
MKSODTVersion.versionRefresh()
        │
        ├── 参数化 DELETE 旧 OD/ODL（AD Object + Attachment）
        ├── 按 EntityType 扫描 AD 表（含 _Trl）
        │       └── ODTQueryHelper.listPOs（失败即抛异常）
        ├── exportObjectData → requireObjectUuid → save OD
        ├── exportObjectDataLine → save ODL（同一 trx）
        └── exportProcessAttachments → Attachment OD
```

---

## 3. 构建与依赖

```text
父 POM      : idempiere.extension.parent (${revision})   Tycho 4.0.8
Aggregator  : org.idempiere.myappx.plugin.odt.main (pom)
Plugin      : org.idempiere.myappx.plugin.odt (eclipse-plugin)
P2          : org.idempiere.myappx.plugin.odt.p2 (eclipse-repository, lean)
```

**Require-Bundle：**

- `org.adempiere.base`
- `org.adempiere.base.process`
- `org.adempiere.plugin.utils`

**Import-Package：** `org.osgi.framework`、`org.osgi.service.event`、JAXP（`org.xml.sax`、`javax.xml.parsers`、`javax.xml.transform*`）

**Export-Package：** `org.idempiere.myappx.plugin.odt`、`.model`、`.process`（供其它插件继承 Activator / 使用模型）

**构建：**

```bash
# 须在 myappx-plugins 父工程下执行（构建 plugin + p2）
cd myappx-plugins
mvn -f myappx-plugin-odt/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify

# 仅构建插件 bundle（含上游 parent）
mvn "-Drevision=14.0.0-SNAPSHOT" clean verify -pl myappx-plugin-odt/org.idempiere.myappx.plugin.odt -am
```

> 根目录用 `-pl myappx-plugin-odt` 只会构建 aggregator 本身，不会进入子模块；完整 reactor 请用 `-f myappx-plugin-odt/pom.xml`。

单独在子模块目录运行 `mvn compile` 可能因 `${revision}` 等 manifest 占位符未解析而失败。

P2 模块 `includeAllDependencies=false`，产物为 lean site，只含本 Bundle，不含平台 JAR。

---

## 4. 项目结构

```text
myappx-plugin-odt/                                      # aggregator
├── pom.xml
├── .project                                            # org.idempiere.myappx.plugin.odt.main
├── README.md                                           # 中文（本文件）
├── README.en.md                                        # English
├── org.idempiere.myappx.plugin.odt/                    # eclipse-plugin
│   ├── META-INF/
│   │   ├── MANIFEST.MF
│   │   └── ODTPackage.xml                              # Activator 读取此文件（可选）
│   ├── OSGI-INF/
│   │   ├── ODTProcessFactoryImpl.xml
│   │   └── ODTModelFactoryImpl.xml
│   ├── src/org/idempiere/myappx/plugin/odt/
│   │   ├── Activator.java
│   │   ├── ODT2PackActivator.java
│   │   ├── ODTProcessFactoryImpl.java
│   │   ├── ODTModelFactoryImpl.java
│   │   ├── service/
│   │   │   ├── ODTInstallEngine.java
│   │   │   └── ODTVersionCheck.java
│   │   ├── model/                                      # 含 I_KS_* / X_KS_*（Generate Model，勿手改）
│   │   ├── process/                                    # 8 个 @Process
│   │   └── util/ODTLog.java
│   ├── test/
│   │   └── ODTPackage_HelloWorld.xml                   # 示例包（不打进 Bundle）
│   ├── build.properties                                # bin.includes: META-INF/, ., OSGI-INF/
│   └── pom.xml
└── org.idempiere.myappx.plugin.odt.p2/                 # eclipse-repository
    ├── category.xml
    └── pom.xml
```

`I_*` / `X_*` 仅由 iDempiere Generate Model 生成，业务逻辑只写在 `M*` 与 service 中。

---

## 5. 数据模型

四张表均为 **System** 访问级别。`ODTModelFactoryImpl` 将表名映射到 `M*` 实现。

### 5.1 KS_ODT* 表

| 表名 | 模型类 | 说明 |
|------|--------|------|
| `KS_ODTPackage` | `MKSODTPackage` | 包头：Name、ObjectType、EntityType、IsImportedODT；按钮列 ExportPackage / CopyPackage |
| `KS_ODTVersion` | `MKSODTVersion` | 版本：VersionNo、Version_Status、SystemVersion；按钮列 Install / Uninstall / Refresh / Release / LinkEntityType |
| `KS_ODTObjectData` | `MKSODTObjectData` | 一条 AD 记录或附件：AD_Table_ID、ObjectData_UUID、SeqNo、SQL_Apply/Unapply、AttachmentPayload、isCoreID、MessgeLog |
| `KS_ODTObjectDataLine` | `MKSODTObjectDataLine` | 字段行：AD_Column_ID、New/Old Value、New/Old ID、New/Old UUID、IsNew/OldNullValue |

新建 **非导入** 的 `KS_ODTPackage` 时，`afterSave` 自动创建 `VersionNo=1`、状态 **Draft**、名称为 `{PackageName}-V1` 的版本。导入包（`IsImportedODT=Y`）不会自动建版本。

导出 XML 时 `IsImportedODT` 固定写 `Y`，以便目标库导入后不再自动插入 Draft 版本。

### 5.2 列表常量

| 字段 | 取值 |
|------|------|
| `ObjectType` | `ODT_APP`（Application） |
| `Version_Status` | `Draft`、`Released` |
| `ObjectData_Type` | `AD Object`、`Attachment` |
| `ObjectData_Action` | `Insert`、`Update`、`Delete`、`N/A` |
| `ObjectData_Status` | `Applied`、`Failed`、`Unapplied` |

Refresh/导出写入的 OD 默认 `ObjectData_Status=Applied`、`ObjectData_Action=N/A`。**Export Package** 写出 XML 时会把每条 OD 的 Status 写成 `Unapplied`、Action 写成 `N/A`。

### 5.3 ObjectData 常用字段

| 字段 | 说明 |
|------|------|
| `ObjectData_UUID` | 目标 AD 记录 UUID；安装时用于查找或创建 PO |
| `SQL_Apply` | 可选；PO 保存后执行，多条以 `--//--` 分隔 |
| `SQL_Unapply` | 可选；导出写入 XML，**安装/卸载路径均未执行** |
| `AttachmentPayload` | Base64 ZIP；仅 `Attachment` 类型 |
| `isCoreID` | 是否核心 ID（字典字段；安装引擎当前不据此分支） |
| `MessgeLog` | 消息日志（列名拼写与 AD 一致） |
| `Record_ID` | 源环境记录 ID；导入时若目标已有同 UUID 的 PO 则回填其 ID，否则为 0 |

---

## 6. ODTPackage.xml 格式

### 6.1 文件位置与命名

| 场景 | 路径 |
|------|------|
| `Activator` | **单个** `META-INF/ODTPackage.xml` |
| `ODT2PackActivator` | `META-INF/ODTPackage*.xml`（按文件名排序依次安装；失败则停止后续文件） |
| Export Package | 临时文件 `ODTPackage_{EntityType}.xml`，附加到包记录后删除临时文件 |
| 示例 | `test/ODTPackage_HelloWorld.xml`（不进入 `bin.includes`） |

### 6.2 结构示例

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<ODT_IDEMPIERE>
  <ODTPackage ID="..." IsImportedODT="Y" ObjectType="ODT_APP" UUID="...">
    <Name><![CDATA[MyAppx ODT]]></Name>
    <Description><![CDATA[MyAppx ObjectData Tool]]></Description>
    <ODTVersion ID="..." UUID="..." VersionNo="1" Version_Status="Draft">
      <Name><![CDATA[MyAppx ODT-V1]]></Name>
      <Description><![CDATA[]]></Description>
      <SystemVersion><![CDATA[]]></SystemVersion>
      <ODTObjectData AD_Table_ID="882" ObjectData_UUID="..." ObjectData_Type="AD Object"
                     ObjectData_Status="Unapplied" ObjectData_Action="N/A" SeqNo="10" ...>
        <Name><![CDATA[AD_EntityType-...]]></Name>
        <SQL_Apply><![CDATA[]]></SQL_Apply>
        <SQL_Unapply><![CDATA[]]></SQL_Unapply>
        <MessgeLog><![CDATA[]]></MessgeLog>
        <ODTObjectDataLine AD_Column_ID="...">
          <NewValue xml:space="preserve"><![CDATA[...]]></NewValue>
          <OldValue xml:space="preserve"><![CDATA[]]></OldValue>
          <IsNewNullValue>false</IsNewNullValue>
          <NewID>0</NewID>
          <NewUUID/>
        </ODTObjectDataLine>
      </ODTObjectData>
    </ODTVersion>
  </ODTPackage>
</ODT_IDEMPIERE>
```

### 6.3 元素说明

| 元素 / 属性 | 说明 |
|-------------|------|
| `ODT_IDEMPIERE` | 根元素，可含多个 `ODTPackage` |
| `ODTPackage` | `UUID` 用于包去重；`IsImportedODT=Y` 表示来自导入/安装 |
| `ODTVersion` | Bundle 安装时 `VersionNo` 须 **大于** 已安装版本；`UUID` 用于版本去重 |
| `ODTObjectData` | 一条 AD 记录；`ObjectData_UUID` 用于查找或创建 PO |
| `ODTObjectDataLine` | 一个字段变更 |
| `SQL_Apply` | 可选；安装时在 PO 保存后执行 |
| `SQL_Unapply` | 可选；预留卸载用，当前未执行 |
| `AttachmentPayload` | 附件 ZIP 的 Base64；导出时带 `encoding="base64"` |
| CDATA | 导出时带 `xml:space="preserve"`，避免 Transformer 缩进污染空白。导入 **不 trim** `NewValue` / `OldValue`（空串、Script/SQL 空白需保留）。`Name`、`SQL_Apply`、`SQL_Unapply` 在导入 KS_ODT* 时会 trim |

包 / 版本按 UUID 查找：已存在则更新，不存在则新建。ObjectData **始终新建**（不按 UUID 合并），同一 Version UUID 重复 Import 可能产生重复 OD 行。

---

## 7. 安装流程

### 7.1 三种安装入口

| 入口 | 调用链 | 写入 KS_ODT* |
|------|--------|--------------|
| Bundle `Activator` | `ODTVersionCheck`（strict）→ `installFromVersionXml` → `importFromXmlNode` | 是 |
| Bundle `ODT2PackActivator` | 同上（非 strict；多文件）；导入后从 XML 回写 Package.EntityType | 是 |
| Process **Package Install** | `MKSODTVersion.packageInstall()` → `installFromVersion` | 否（已存在） |
| Process **Import Package** + Apply | `importFromXmlNode` → `apply()` | 导入时写入；**Apply 当前为空实现** |

所有 AD 对象安装逻辑由 **`ODTInstallEngine`** 统一实现。

安装上下文：System Client（0）/ System User / System Role。`ODT2PackActivator` 另在 `setupPackInContext()` 中设置 System User/Role。

### 7.2 单条 AD Object 处理顺序

1. `ODTPOHelper.generatePO(uuid, table, trx)` — 参数化 UUID 查找或新建 PO
2. 遍历 ODL，`ODTPOBuilder.buildPO(...)` 设置字段值
3. `AD_TreeNodeMM` 新建时按 `AD_Tree_ID + Node_ID` reload，再写 SeqNo / Parent_ID / UUID
4. `ODTPOHelper.reconcileTrlPOIfExistsByNaturalKey(...)` — `*_Trl` 按父键 + `AD_Language`（及 Client）合并到已有行
5. `po.saveEx()`
6. PostgreSQL：对强制空串占位列执行 `UPDATE ... SET col=''`（见 7.6）
7. 若为 `AD_Column`：`ODTDdlService.queueColumnSync(...)` 加入待同步队列（跳过视图列、虚拟列）
8. 执行 `SQL_Apply`（若有）
9. 全部 AD Object 完成后，处理 `Attachment` 类型
10. `ODTPostInstallService.postInstallPackage(...)` 批量 DDL 与后处理
11. Bundle 路径：`CacheMgt.reset()` 后 `importFromXmlNode(...)`

### 7.3 Bundle 版本校验（ODTVersionCheck）

| 条件 | Activator（`strictDowngrade=true`） | ODT2PackActivator（`false`） |
|------|-------------------------------------|------------------------------|
| `KS_ODTPackage` 不在 AD | bootstrap 安装 | bootstrap 安装 |
| `VersionNo > 已安装` | 安装（不卸载已安装版本） | 安装（不卸载已安装版本） |
| `VersionNo == 已安装` | **中止整个 `install()`**（`return` 退出循环） | `continue` 跳过该包 |
| `VersionNo < 已安装` | **中止整个 `install()`** | 当作已安装跳过 |

因此：`Activator` 在同一 XML 含多个 Package 时，第一个 SKIP 会丢掉后续包。多包请合并，或改用 `ODT2PackActivator`。

### 7.4 SQL_Apply 行为

- 在每条 ObjectData 的 PO 保存后执行
- 多条 SQL 以 `--//--` 分隔
- 单条失败记 SEVERE 日志，**安装继续**（不 fail-fast）
- Bundle 路径 `trxName=null`；Process 路径使用版本事务
- 从 XML 安装时会对 `SQL_Apply` 文本 `trim()`

### 7.5 ODT2PackActivator 三阶段

```text
preInstallPackage()    # 空钩子，供子类覆盖
    ↓
packInAll()
    ├── packInODT()      → META-INF/ODTPackage*.xml
    ├── packIn2Pack()    → META-INF/2Pack_*.zip（Incremental2PackActivator）
    └── packInFolder()   → META-INF/ODT2Pack-*.zip
    ↓
postInstallPackage()   # 空钩子
```

`frameworkStarted()`：若 iDempiere 已启动则在线程池异步执行；否则监听 `SERVER_START`。执行前后切换 ClassLoader，结束时 `ServerContext.dispose()`。

### 7.6 空字符串与强制列

iDempiere `PO` 插入时会把 `""` 绑成 SQL `NULL`。ODT XML 中 `<NewValue/>` 且 `IsNewNullValue=false` 表示「空串」而非「跳过」。

对 **新建** 记录上 **强制（mandatory）文本列**：

1. `ODTPOBuilder` 先写入单空格 `" "` 作为占位
2. `saveEx()` 成功后，`persistEmptyStrings()` 仅在 **PostgreSQL** 上按 UUID `UPDATE` 为真正的 `''`
3. Oracle 将 `''` 视为 NULL，故跳过该步

已存在行不会用空占位覆盖，以免更新时抹掉可选文本。

### 7.7 安装时跳过 / 特殊列

`ODTPOBuilder.buildPO` 会跳过或特殊处理：

| 条件 | 行为 |
|------|------|
| `AD_Chart_ID` | 跳过 |
| `AD_Client_ID`（非 `_Trl` 表） | 跳过 |
| `Created` / `Updated` / `UpdatedBy` | 跳过 |
| 主键列（除 `AD_EntityType` 与 `_Trl`） | 跳过 |
| `C_BPartner` 的 TOTALOPENBALANCE / ACTUALLIFETIMEVALUE / FIRSTSALE / SO_CREDITUSED | 跳过 |
| `AD_User` 的 EMAILVERIFY / EMAILVERIFYDATE / LASTCONTACT / LASTRESULT | 跳过 |
| `AD_Org_ID`（目标表不是 `AD_Org`） | 用 `NewID` 调用 `setAD_Org_ID` |
| `PAttribute` | 写入 `0` |
| 非更新列 | 仅允许 DocumentNo、MovementType、IsSOTrx、IsTransferred、`AD_Tree.TreeType`、`AD_EntityType.EntityType`、`_Trl.AD_Language` |

外键列优先用 `NewUUID` 解析目标 PO；UUID 无效且列可更新时置 `null`。

---

## 8. 安装后 DDL 处理

安装循环中不逐列调用 `ColumnSync`，改为与 2Pack 类似的批量 DDL，由 **`ODTPostInstallService.postInstallPackage`** 编排：

```text
ODTDdlService.synchronizeColumns()      → ADD/MODIFY 列（不含 FK）
        ↓
ODTIndexService.validateTableIndexes()  → TableIndexValidate
        ↓
ODTDdlService.deferForeignKeys()        → 延迟创建外键
        ↓
doSequencCheck()                        → Sequence Check（AD_Process_ID=258）
        ↓
doRoleAccessUpdate()                    → Role Access Update（AD_Process_ID=295）
```

后两个进程以 System Client、User 100 启动。

### 8.1 API

```java
ODTPostInstallService.postInstallPackage(ctx);
ODTPostInstallService.postInstallPackage(ctx, entityType, tableIndexUuids, log, trxName);
ODTPostInstallService.postInstallPackage(ctx, entityType, columnIds, tableIndexUuids, log, trxName);
```

| 参数 | 说明 |
|------|------|
| `entityType` | `tableIndexUuids` 为空时，按 EntityType 查找全部 `AD_TableIndex` |
| `columnIds` | `queueColumnSync` 收集的 `AD_Column_ID`（`LinkedHashSet` 保序） |
| `tableIndexUuids` | 本版本 `AD_TableIndex` UUID 列表（精确校验，优先于 EntityType 扫描） |
| `trxName` | 为 `null` 时各 DDL 阶段各自创建临时 `ODTDDL*` 事务 |

### 8.2 列同步（ODTDdlService）

- 跳过视图列、虚拟列
- 排序：同表 → Key 列优先 → Column_ID
- 表不存在时先建表，再同步列
- SysConfig `2PACK_COMMIT_DDL=Y`：DDL 前/后 `trx.commit()`（与 2Pack 一致）
- Oracle CLOB/BLOB 的 MODIFY 有特殊修正
- 列同步失败抛 `AdempiereException`（与 SQL_Apply 不同，会中止安装）

### 8.3 索引校验（ODTIndexService）

调用 `TableIndexValidate.validateTableIndex()`。`IsCreateConstraint=Y` 时在数据库创建 UNIQUE / PRIMARY KEY。`TableIndexValidate` 需要有效 `trxName`，不能传 `null`（由 `openDdlTrx` / `finishDdlTrx` 管理）。失败抛异常。

---

## 9. 后台进程（Process）

位于 `org.idempiere.myappx.plugin.odt.process`，由 `@Process` + `ODTProcessFactoryImpl` 注册。进程从当前记录 `getRecord_ID()` 取上下文。

| 进程 | 记录 | 状态 | 说明 |
|------|------|------|------|
| **Package Install** | `KS_ODTVersion` | 可用 | `packageInstall()` → `ODTInstallEngine.installFromVersion` |
| **Export Package** | `KS_ODTPackage` | 可用 | 导出 **最新 VersionNo** 为 `ODTPackage_{EntityType}.xml`，附加到包 |
| **Import Package** | （参数） | 可用 | 参数 `FileName`（文件或目录下全部 `.xml`）、`Apply`。Apply 调用空的 `apply()` |
| **Version Refresh** | `KS_ODTVersion` | 可用 | 按 Package.EntityType 重扫 AD，重建 OD/ODL |
| **Link Entity Type** | `KS_ODTVersion` | 可用 | 参数 `EntityType`（默认 `"C"`）。删掉本版本已有 `AD_EntityType` OD，再导出该类型，并把其它 OD 上 EntityType 列的 NewValue 改成该值；同时更新 Package.EntityType |
| **Version Release** | `KS_ODTVersion` | 未实现 | 加载版本后直接返回成功，不改 `Version_Status` |
| **Package Uninstall** | `KS_ODTVersion` | 未实现 | 加载版本后直接返回成功，不调用 `packageUninstal()` |
| **Copy Package** | `KS_ODTPackage` | 未实现 | 参数 `KS_ODTPackage_ID`（源）、`EntityType`、`IsNewUUID`；返回 `Not implemented yet!` |

---

## 10. 核心服务与工具类

### 10.1 service 包

| 类 | 职责 |
|----|------|
| `ODTInstallEngine` | `installFromVersion`（Process/DB）；`installFromVersionXml`（Bundle/XML，不写 KS_ODT*） |
| `ODTVersionCheck` | `isOdtRuntimeAvailable`、`resolveInstalledVersionNo`、`evaluate` |

### 10.2 model 包 — 安装

| 类 | 职责 |
|----|------|
| `ODTPOHelper` | `findPO` / `generatePO`；`reconcileTrlPOIfExistsByNaturalKey` |
| `ODTPOBuilder` | `buildPO`；`takeEmptyStringColumns` |
| `ODTDdlService` | `queueColumnSync`、`synchronizeColumns`、`deferForeignKeys`、DDL 事务 |
| `ODTIndexService` | `validateTableIndexes`、`collectTableIndexUuidsFromVersion` |
| `ODTPostInstallService` | `postInstallPackage`；`doSequencCheck` / `doRoleAccessUpdate` |

### 10.3 model 包 — 导出与 XML

| 类 | 职责 |
|----|------|
| `ODTLookupHelper` | 解析 Table/Search 目标表；Identifier 串（分隔符 `#@#`） |
| `ODTQueryHelper` | `listPOs`；失败抛 `AdempiereException` |
| `ODTXmlHelper` | CDATA + `xml:space="preserve"`；从 XML 提取 EntityType / TableIndex UUID |
| `ODTConstants` | 日期格式 `yyyy-MM-dd HH:mm:ss`；`ID_VALUE_SEPARATOR = "#@#"` |

Identifier 串形态：`ParentIdCol1#@#ParentIdCol2#@#...#@#IdCol1#@#IdCol2`。单据表（`C_Invoice` / `M_InOut` / `C_Order` / `C_Payment` / `M_Requisition`）仅保留 `DocumentNo` 作为 Identifier。

Lookup 解析顺序：Table Reference → 列名 `*_ID` 对应表存在则用该表 → Val Rule 硬编码例外（仅列名对不上目标表时）：

| AD_Val_Rule_ID | 表 |
|----------------|-----|
| 230 | `C_BPartner`（如 `Bill_BPartner_ID`） |
| 231 | `M_Product` |
| 184 | `M_Lot` |
| 272 / 218 | `C_Order` |
| 220 | `C_Invoice` |
| 158 | `AD_Role` |

Val Rule 只是 UI 过滤，不是表解析器。解析失败且列有值时 WARNING。

导出 FK 时的硬编码：

| 条件 | 处理 |
|------|------|
| `AD_User` ID = 0 | 改为 SuperUser **100** |
| `AD_Menu` ID = -1 | 改为 Top Menu **0** |
| `AD_Client` ID = 0 | UUID `11237b53-9592-4af1-b3c5-afd216514b5d` |
| `AD_Org` ID = 0 | UUID `3ef41ffc-8ea9-454a-afa2-22949f402ff5` |
| `AD_Menu` ID = 0 | UUID 置空（主菜单） |
| Identifier：`AD_Org` 0 | `*` |
| Identifier：`AD_User` 0 | `System` |
| Identifier：`AD_Role` 0 | `System Administrator` |

### 10.4 DisplayType 支持范围

导出（`exportObjectDataLine`）与安装（`buildPO`）覆盖常见 DisplayType。局限：

| DisplayType | 说明 |
|-------------|------|
| TableDir / Table / Search / Locator / Location | 支持；FK 导出为 Identifier + NewUUID。Table 类型安装/导出时 `AD_Val_Rule_ID` **硬编码为 0** |
| ID | 仅 `AD_TreeNodeMM.Node_ID/Parent_ID` 映射到 `AD_Menu`；其它 ID 列导出 WARNING |
| PAttribute | 导出 WARNING；安装写 `0` |
| Integer / Number / Amount / CostPrice / Quantity / Date* / Boolean / String | 支持 |

---

## 11. 附件处理

`AttachmentODTUtil` 处理 **流程对象（`AD_Process`）** 附件：

| 方向 | 方法 |
|------|------|
| 导出 | `exportZipAsBase64(MAttachment)` — `saveAsZip()` 后 Base64 |
| DB 安装 | `installProcessAttachment(ctx, odtod, trxName, log)` |
| XML/Bundle 安装 | `installProcessAttachmentFromXml(ctx, element, log)` |

Refresh 将附件写成 `ObjectData_Type=Attachment`，`AD_Table_ID` 仍为 `AD_Process`，`ObjectData_UUID` 为流程 UUID。安装在主 ObjectData 循环 **之后** 执行，避免附件早于流程元数据。流程 PO 按 UUID `findPO`。

---

## 12. Version Refresh

`MKSODTVersion.versionRefresh()` 由 **Version Refresh** 调用。Package 上 **EntityType 必填**，否则 `@FillMandatory@`。

过滤条件：`EntityType=? AND AD_Client_ID=0`。`_Trl` 行取其系统级父记录的 EntityType。`AD_TreeNodeMM`：`Node_ID IN (SELECT AD_Menu_ID FROM AD_Menu WHERE EntityType=? AND AD_Client_ID=0) AND AD_Client_ID=0`。

### 12.1 执行步骤

1. 参数化 DELETE 本版本中 `ObjectData_Type IN ('AD Object','Attachment')` 的 ODL 与 OD
2. 重置 SeqNo 计数器
3. 按 EntityType 依次导出下表
4. 在 `AD_Process` 之后导出流程附件

### 12.2 导出表清单（顺序即 SeqNo 顺序）

`AD_EntityType`（固定 SeqNo=10）, `AD_SysConfig`, `AD_Message`(+Trl), `AD_Rule`, `AD_Window`(+Trl), `AD_Form`(+Trl), `AD_Element`(+Trl), `AD_Val_Rule`, `AD_Reference`(+Trl), `AD_Process`（随即附件）, `AD_Process`(+Trl,+Para,+Para_Trl), `AD_Table`(+Trl), `AD_Column`(+Trl, `AD_Table_ID, isKey desc`), `AD_TableIndex`, `AD_IndexColumn`（`AD_TableIndex_ID, seqNO`）, `AD_Ref_Table`, `AD_Tab`(+Trl, `AD_Window_ID, seqNo`), `AD_FieldGroup`(+Trl), `AD_Field`(+Trl, `AD_Tab_ID, seqNo`), `AD_InfoWindow`, `AD_InfoColumn`（`AD_InfoWindow_ID, seqNo`）, `AD_Ref_List`(+Trl), `AD_ToolBarButton`, `AD_Workflow`, `AD_WF_Node`, `AD_WF_NodeNext`, `AD_Menu`(+Trl), `AD_TreeNodeMM`

未覆盖的例子：`AD_InfoWindow_Trl`、打印格式、报表、大部分业务表数据。

### 12.3 健壮性

| 机制 | 说明 |
|------|------|
| **UUID 校验** | `requireObjectUuid()` — 无 UUID 列、记录 UUID 空、FK 目标不存在时抛异常，**整次 Refresh 失败** |
| **事务** | OD/ODL 使用 Process `trxName`，失败可回滚 |
| **查询失败可见** | `listPOs` 失败抛异常，不静默空数组 |
| **虚拟列** | 导出时跳过 |

若记录缺少 UUID，Refresh 会报表名与 Record_ID。

### 12.4 性能

- `listPOs` + 参数化 WHERE
- SeqNo：每类 ObjectData 只查一次 `MAX(SeqNo)`，内存 +10；Attachment 用独立 `allocateAnySeqNo`

---

## 13. 2Pack 与 ODT2Pack zip

仅 **`ODT2PackActivator`** 执行（`Activator` 的 `packIn()` 为空）。

### 13.1 2Pack_*.zip

通过 iDempiere 2Pack 服务 Pack In。已成功安装的包以 `AD_Package_Imp.Name` + `PK_Status='Completed successfully'` 记录，用于跳过同版本。版本号超过三段且含 `.v` / `.qualifier` 时截成 `major.minor.patch`。

### 13.2 ODT2Pack-*.zip

命名：`ODT2Pack-{major.minor.patch}_{ClientValue}_{Description}.zip`。版本为三段数字，例如 `ODT2Pack-1.0.0_SYSTEM_MYAPPX.AIC.zip`。

- 同一 Bundle 内按版本号升序安装；版本相同再按文件名
- ClientValue 在文件名第二段；`ALL-CLIENTS` 或 `ALL-CLIENTS-{seed}` 可对多租户安装（seed 客户优先）
- 文件名不符合约定则跳过该文件
- 找不到 Client / seed 则该 zip 失败并停止后续 zip
- 已成功安装的包以完整文件名 + 目标 Client + `PK_Status='Completed successfully'` 跳过
- 成功后（ALL-CLIENTS）写入 `AD_Package_Imp_Proc` / `AD_Package_Imp` 避免重复
- 需要 DB 锁；必要时创建 `MSession`（WebSession=`ODT2PackActivator`）以写 `AD_ChangeLog`
- 资源先复制到临时 zip 再 merge / directMerge

参考：[2Pack Pack In/Out](https://wiki.idempiere.org/en/Developing_Plug-Ins_-_2Pack_-_Pack_In/Out) · [NF5.1 Automatic External Packin](https://wiki.idempiere.org/en/NF5.1_Automatic_External_Packin)

---

## 14. 典型工作流

### 14.1 插件开发：字典随 Bundle 发布

1. 在 iDempiere 中维护 AD（EntityType = 插件自身的实体类型）
2. 创建 `KS_ODTPackage`，设置 EntityType（或 **Link Entity Type**）
3. **Version Refresh** → **Export Package**
4. 将附件 XML 复制为该插件 `META-INF/ODTPackage.xml`，递增 `VersionNo`
5. 插件 Activator 继承 `ODT2PackActivator`（需先部署本 ODT Bundle）
6. 在 `myappx-plugins` 下构建并部署

### 14.2 环境间迁移（不打进 Bundle）

1. 源环境：**Version Refresh** → **Export Package**
2. 目标环境：**Import Package**（`Apply=N` 只写入 KS_ODT*）
3. 目标环境：**Package Install** 应用到 AD

> Import 的 `Apply=Y` 不触发安装，须再运行 **Package Install**。

### 14.3 首次部署 ODT 插件本身

1. 将 `org.idempiere.myappx.plugin.odt` 放入 plugins 并启动
2. 若 Bundle 含 `META-INF/ODTPackage.xml`：`Activator` bootstrap 安装 ODT 自身字典与表
3. 若 XML 不在 Bundle 中：在源环境 Refresh/Export 后，用 **Import Package** + **Package Install**，或把 XML 放入 `META-INF` 后重建
4. 后续升级：递增 XML `VersionNo`，Bundle 激活时自动安装新版本

### 14.4 依赖本插件的其它插件

`ODT2PackActivator` 已导出。依赖插件应：

1. Require-Bundle `org.idempiere.myappx.plugin.odt`
2. `Activator extends ODT2PackActivator`
3. 提供 `META-INF/ODTPackage.xml`（或 `ODTPackage*.xml`），可选 `ODT2Pack-*.zip`

---

## 15. 测试与示例

| 资源 | 用途 |
|------|------|
| `org.idempiere.myappx.plugin.odt/test/ODTPackage_HelloWorld.xml` | 完整示例：包名 **Test HelloWorld**，EntityType **Test_HelloWorld**，`ObjectType=ODT_APP`，VersionNo=1 Draft。可用于 Import Package 或学习 XML 结构 |

`build.properties` 未包含 `test/`，该 XML **不会**打进 JAR。无 JUnit / Tycho 测试模块。建议构建后在 iDempiere 中手工验证 Refresh、Install、Bundle 安装。

---

## 16. P2 部署

1. 在 `myappx-plugins` 根目录：

```bash
mvn -f myappx-plugin-odt/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify
```

2. 产物：`org.idempiere.myappx.plugin.odt.p2/target/repository`（lean：仅 ODT bundle）

3. 用 iDempiere / p2 director 指向该 repository，category **MyAppx ODT Extensions**（`myappx.odt.extensions`）。也可将 `target/*.jar` 放入实例 `plugins` 目录。

4. **myappx-portable** 建议用 OSGi console 热安装（见该仓库 README）。若 Bundle 停在 STARTING：

```text
sta org.idempiere.myappx.plugin.odt
```

---

## 17. 日志

`ODTLog` 统一前缀 `[ODT][Module]`，键值对用 ` | ` 连接，便于 grep。

| Module | 来源 |
|--------|------|
| `Activator` | `Activator` |
| `2Pack` | `ODT2PackActivator` |
| `Install` | `ODTInstallEngine` |
| `Export` | Refresh / LinkEntityType |
| `Version` | `ODTVersionCheck` |
| `DDL` / `Index` / `PostInstall` | 后处理 |
| `Attachment` / `Lookup` / `PO` / `Query` / `Process` / `Package` | 对应层 |

将 iDempiere 日志级别调到 **FINE** 可看到逐列赋值与 lookup 细节。

---

## 18. 故障排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| Bundle 启动后无 ODT 窗口/表 | `META-INF/ODTPackage.xml` 缺失，Activator 直接返回 | 放入导出的 XML 并重建，或 Import + Install |
| `Table Name Not Found - KS_ODTPackage` | 首次安装前查询 ODT 表 | bootstrap 会跳过版本查询；确认 `KS_ODTPackage` 尚未注册时走 `ODTVersionCheck` |
| `TableIndexValidate failed` / `No Transaction Name` | 索引校验未获得 trx | 确认走 `ODTPostInstallService` + `openDdlTrx` |
| Refresh 报 UUID | AD 记录缺 UUID 或 FK 目标不存在 | 补 UUID 或清理无效数据 |
| Refresh 报 `listPOs failed` | WHERE 或表结构 | 查看日志中的表名与 SQL 栈 |
| Bundle 安装后 KS_ODT* 无数据 | `importFromXmlNode` 未执行或版本被跳过 | 检查 `VersionNo`；看 `[ODT][Activator]` / `[ODT][Version]` |
| Import 后 AD 未变 | 未跑 Package Install；Apply=Y 无效 | 手动 **Package Install** |
| `SQL_Apply failed` 但安装继续 | 设计为记日志后继续 | 修正 ObjectData 中的 SQL |
| 多 Package XML 只装第一个 | Activator 在 SKIP 时 `return` | 合并为单 Package 或用 `ODT2PackActivator` |
| 强制文本列 NOT NULL 失败 | 空 `NewValue` 被当成 NULL | 确认 `IsNewNullValue=false`；PostgreSQL 会在 save 后写 `''` |
| `_Trl` 主键冲突 | 自然键已存在 | 引擎会 merge 到已有行；仍失败则检查 Language / 父 ID |
| 重复 Import 出现重复 OD | ObjectData 始终 insert | 先删版本下 OD，或换新 Version UUID |

---

## 19. 已知限制

| 项 | 说明 |
|----|------|
| Bundle 自举包 | `Activator` 仅在存在 `META-INF/ODTPackage.xml` 时安装；也可用 Import + Package Install |
| 版本升级 | 只安装更高 `VersionNo`，不卸载已安装版本；`packageUninstal()` 为空 |
| `SQL_Unapply` | 字段会导出/导入，安装与卸载均不执行 |
| `MKSODTPackage.apply()` | 空方法；Import `Apply=Y` 不触发安装 |
| `CopyPackageProcess` / `VersionReleaseProcess` / `PackageUninstallProcess` | 未实现 |
| `SQL_Apply` 失败 | 记日志后继续，非 fail-fast |
| DisplayType | 非 TreeNodeMM 的 ID、PAttribute 不完整；Table 路径 Val Rule 恒为 0 |
| Export/Install 映射 | 导出与 `ODTPOBuilder` 分离，存在往返漂移风险 |
| ObjectData 导入 | 不按 UUID 合并，重复 Import 可能重复行 |
| `Activator` 多包 | 第一个 SKIP 中止整个文件 |
| 自动化测试 | 无 JUnit/Tycho 测试模块 |
| Refresh 覆盖面 | 仅列出的 AD 表 + 流程附件，不含打印格式等 |

---

## 20. 相关系统配置

| SysConfig | 说明 |
|-----------|------|
| `2PACK_COMMIT_DDL` | 为 `Y` 时，ODT 列同步与索引校验在 DDL 前后提交事务（`MSysConfig.TWOPACK_COMMIT_DDL`） |

---

## 21. 许可证

本插件与 [iDempiere](https://www.idempiere.org/) 相同，采用 [GNU General Public License Version 2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)（GPLv2）。完整文本见 [LICENSE.md](LICENSE.md)。

贡献者：ken.longnan@gmail.com

*Bundle: `org.idempiere.myappx.plugin.odt`*
