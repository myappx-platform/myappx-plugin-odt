# MyAppx Plugin ODT

**Language:** English | [中文](README.md)

[![License](https://img.shields.io/badge/license-GPL--2.0-brightgreen.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)

**MyAppx Plugin : ObjectData Tool (ODT)** is an OSGi plugin for [iDempiere](https://www.idempiere.org/). It manages Application Dictionary (AD) metadata as structured object data, with packaging, versioning, import/export, and install. It can optionally work with iDempiere 2Pack.

Other MyAppx plugins can extend `ODT2PackActivator` so that their own ODT package and `ODT2Pack-*.zip` files are installed when the bundle starts.

| Item | Value |
|------|--------|
| Aggregator | `org.idempiere.myappx.plugin.odt.main` |
| Bundle Symbolic Name | `org.idempiere.myappx.plugin.odt` (`singleton:=true`) |
| Bundle Version | `14.0.0.qualifier` |
| P2 repository | `org.idempiere.myappx.plugin.odt.p2` (category `myappx.odt.extensions`) |
| Default Activator | `Activator` (ODT only; no 2Pack) |
| Full-deploy Activator | `ODT2PackActivator` (ODT + `2Pack_*.zip` + `ODT2Pack-*.zip`) |
| Java | 17+ (`JavaSE-17`) |
| iDempiere | 14 |
| Build | Maven / Tycho 4.x (`eclipse-plugin` + `eclipse-repository`) |
| Parent | `idempiere.extension.parent` |
| Access level | System (AccessLevel = 4) |
| Entity Type (extension repo) | `MYAPPX.ODT` |
| License | [GNU General Public License Version 2](LICENSE.md) |
| Vendor | Ken Longnan \<ken.longnan@gmail.com\> |

---

## Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Build and dependencies](#3-build-and-dependencies)
4. [Project layout](#4-project-layout)
5. [Data model](#5-data-model)
6. [ODTPackage.xml format](#6-odtpackagexml-format)
7. [Install pipeline](#7-install-pipeline)
8. [Post-install DDL](#8-post-install-ddl)
9. [Processes](#9-processes)
10. [Core services and helpers](#10-core-services-and-helpers)
11. [Attachments](#11-attachments)
12. [Version Refresh](#12-version-refresh)
13. [2Pack and ODT2Pack zip](#13-2pack-and-odt2pack-zip)
14. [Typical workflows](#14-typical-workflows)
15. [Tests and samples](#15-tests-and-samples)
16. [P2 deployment](#16-p2-deployment)
17. [Logging](#17-logging)
18. [Troubleshooting](#18-troubleshooting)
19. [Known limitations](#19-known-limitations)
20. [Related system configurator](#20-related-system-configurator)
21. [License](#21-license)

---

## 1. Overview

### 1.1 What ODT does

ODT serializes iDempiere AD objects (windows, tables, fields, menus, indexes, process attachments, and so on) to XML so you can:

- Move metadata between development, test, and production
- Ship `META-INF/ODTPackage.xml` (or `ODTPackage*.xml`) inside a plugin bundle and install it on activation
- Maintain, refresh, export, and install packages in-system as **Package + Version**

Each **ObjectData (OD)** is one row of one AD table, located by **UUID**. Each **ObjectDataLine (ODL)** is one field value (`NewValue` / `NewID` / `NewUUID`).

### 1.2 Relationship to 2Pack

| Mechanism | Role |
|-----------|------|
| **ODT** | Fine-grained AD object migration; good for plugin-owned dictionary and EntityType-based version diffs |
| **2Pack** | Standard iDempiere Pack In/Out; good for full dictionary snapshots and tenant data packs |

Two entry paths:

- **`Activator`** (current `Bundle-Activator`): reads and installs a **single** `META-INF/ODTPackage.xml`. `packIn()` is empty (no 2Pack).
- **`ODT2PackActivator`**: Pre-Install → ODT (`ODTPackage*.xml`) → `2Pack_*.zip` → `ODT2Pack-*.zip`

Plugins that also need 2Pack / `ODT2Pack-*.zip` on start should **extend `ODT2PackActivator`** (this bundle exports that package).

### 1.3 Bootstrap

On **first** install of ODT itself, `KS_ODTPackage` may not yet exist in AD:

1. `ODTVersionCheck.isOdtRuntimeAvailable()` sees that `KS_ODTPackage` is not registered and skips the version DB query
2. `ODTInstallEngine.installFromVersionXml()` applies AD metadata from XML (including later physical DDL)
3. `CacheMgt.get().reset()` refreshes AD cache
4. `MKSODTPackage.importFromXmlNode()` writes `KS_ODT*` business rows

Later bundle upgrades are gated by package **Name** + **VersionNo**. `Activator` installs only a **single** `META-INF/ODTPackage.xml`; if the file is absent, `install()` returns immediately.

---

## 2. Architecture

### 2.1 Layers

```text
┌─────────────────────────────────────────────────────────────────┐
│  Entry                                                          │
│  Activator / ODT2PackActivator / SvrProcess (8)                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  service/                                                       │
│  ODTInstallEngine — unified install pipeline                    │
│  ODTVersionCheck  — bundle version gate / bootstrap             │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  model/                                                         │
│  MKSODTPackage / MKSODTVersion / MKSODTObjectData / Line        │
│  ODTPOHelper / ODTPOBuilder — PO lookup and field apply         │
│  ODTDdlService / ODTIndexService / ODTPostInstallService        │
│  ODTLookupHelper / ODTQueryHelper / ODTXmlHelper                │
│  AttachmentODTUtil — process attachments                        │
└─────────────────────────────────────────────────────────────────┘
```

OSGi Declarative Services:

| Component | Interface | ranking |
|-----------|-----------|---------|
| `ODTModelFactoryImpl` | `IModelFactory` | 5 |
| `ODTProcessFactoryImpl` | `IProcessFactory` | 5 |

Both are `AnnotationBased*` factories scanning `org.idempiere.myappx.plugin.odt.model` / `.process`. `Bundle-ActivationPolicy: lazy`.

### 2.2 Install data flow

```text
                    ┌──────────────────┐
                    │  Entry           │
                    │  Bundle / Process│
                    └────────┬─────────┘
                             │
              ┌──────────────▼──────────────┐
              │  ODTVersionCheck (Bundle)   │
              └──────────────┬──────────────┘
                             │
              ┌──────────────▼──────────────┐
              │  ODTInstallEngine           │
              │  AD Object loop             │
              │  → Attachment loop          │
              └──────────────┬──────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   ODTPOHelper         ODTPOBuilder        SQL_Apply
   generatePO          buildPO             (optional)
         │                   │
         └─────────┬─────────┘
                   ▼
            po.saveEx()
            persistEmptyStrings (PostgreSQL)
            queueColumnSync (AD_Column)
                   │
                   ▼
         ODTPostInstallService
         columns → indexes → FKs → sequence → role
                   │
                   ▼
         importFromXmlNode (bundle path only)
```

### 2.3 Version Refresh data flow

```text
VersionRefreshProcess
        │
        ▼
MKSODTVersion.versionRefresh()
        │
        ├── parameterized DELETE of old OD/ODL (AD Object + Attachment)
        ├── scan AD tables by EntityType (including _Trl)
        │       └── ODTQueryHelper.listPOs (throws on failure)
        ├── exportObjectData → requireObjectUuid → save OD
        ├── exportObjectDataLine → save ODL (same trx)
        └── exportProcessAttachments → Attachment OD
```

---

## 3. Build and dependencies

```text
Parent POM  : idempiere.extension.parent (${revision})   Tycho 4.0.8
Aggregator  : org.idempiere.myappx.plugin.odt.main (pom)
Plugin      : org.idempiere.myappx.plugin.odt (eclipse-plugin)
P2          : org.idempiere.myappx.plugin.odt.p2 (eclipse-repository, lean)
```

**Require-Bundle:** `org.adempiere.base`, `org.adempiere.base.process`, `org.adempiere.plugin.utils`

**Import-Package:** `org.osgi.framework`, `org.osgi.service.event`, JAXP (`org.xml.sax`, `javax.xml.parsers`, `javax.xml.transform*`)

**Export-Package:** `org.idempiere.myappx.plugin.odt`, `.model`, `.process` (so other plugins can extend the activator and use the models)

**Build:**

```bash
# From the myappx-plugins parent (plugin + p2)
cd myappx-plugins
mvn -f myappx-plugin-odt/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify

# Plugin bundle only (with upstream parent)
mvn "-Drevision=14.0.0-SNAPSHOT" clean verify -pl myappx-plugin-odt/org.idempiere.myappx.plugin.odt -am
```

> `-pl myappx-plugin-odt` from the repo root builds only the aggregator POM, not the nested modules. Use `-f myappx-plugin-odt/pom.xml` for the full reactor.

Running `mvn compile` inside a nested module can fail because `${revision}` and other manifest placeholders are unresolved.

The P2 module sets `includeAllDependencies=false` (lean site: this bundle only, no platform JARs).

---

## 4. Project layout

```text
myappx-plugin-odt/                                      # aggregator
├── pom.xml
├── .project                                            # org.idempiere.myappx.plugin.odt.main
├── README.md                                           # Chinese
├── README.en.md                                        # English (this file)
├── org.idempiere.myappx.plugin.odt/                    # eclipse-plugin
│   ├── META-INF/
│   │   ├── MANIFEST.MF
│   │   └── ODTPackage.xml                              # read by Activator when present
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
│   │   ├── model/                                      # I_KS_* / X_KS_* (Generate Model; do not hand-edit)
│   │   ├── process/                                    # 8 @Process classes
│   │   └── util/ODTLog.java
│   ├── test/
│   │   └── ODTPackage_HelloWorld.xml                   # sample (not packaged in the bundle)
│   ├── build.properties                                # bin.includes: META-INF/, ., OSGI-INF/
│   └── pom.xml
└── org.idempiere.myappx.plugin.odt.p2/                 # eclipse-repository
    ├── category.xml
    └── pom.xml
```

`I_*` / `X_*` are produced only by iDempiere Generate Model. Business logic lives in `M*` classes and services.

---

## 5. Data model

All four tables are **System** access level. `ODTModelFactoryImpl` maps table names to the `M*` implementations.

### 5.1 KS_ODT* tables

| Table | Model | Role |
|-------|--------|------|
| `KS_ODTPackage` | `MKSODTPackage` | Package header: Name, ObjectType, EntityType, IsImportedODT; button columns ExportPackage / CopyPackage |
| `KS_ODTVersion` | `MKSODTVersion` | Version: VersionNo, Version_Status, SystemVersion; button columns Install / Uninstall / Refresh / Release / LinkEntityType |
| `KS_ODTObjectData` | `MKSODTObjectData` | One AD row or attachment: AD_Table_ID, ObjectData_UUID, SeqNo, SQL_Apply/Unapply, AttachmentPayload, isCoreID, MessgeLog |
| `KS_ODTObjectDataLine` | `MKSODTObjectDataLine` | Field row: AD_Column_ID, New/Old Value, New/Old ID, New/Old UUID, IsNew/OldNullValue |

On save of a **new non-imported** `KS_ODTPackage`, `afterSave` creates a **Draft** version with `VersionNo=1` and name `{PackageName}-V1`. Imported packages (`IsImportedODT=Y`) do not get an automatic version.

Export always writes `IsImportedODT=Y` so the target does not auto-insert a Draft version.

### 5.2 List values

| Field | Values |
|-------|--------|
| `ObjectType` | `ODT_APP` (Application) |
| `Version_Status` | `Draft`, `Released` |
| `ObjectData_Type` | `AD Object`, `Attachment` |
| `ObjectData_Action` | `Insert`, `Update`, `Delete`, `N/A` |
| `ObjectData_Status` | `Applied`, `Failed`, `Unapplied` |

Refresh/export into the DB defaults OD to `ObjectData_Status=Applied` and `ObjectData_Action=N/A`. **Export Package** writes each OD in XML as `Unapplied` / `N/A`.

### 5.3 ObjectData fields

| Field | Meaning |
|-------|---------|
| `ObjectData_UUID` | Target AD record UUID; used to find or create the PO |
| `SQL_Apply` | Optional; run after PO save; statements separated by `--//--` |
| `SQL_Unapply` | Optional; written to XML; **never executed** on install or uninstall |
| `AttachmentPayload` | Base64 ZIP; `Attachment` type only |
| `isCoreID` | Core ID flag (dictionary field; install engine does not branch on it) |
| `MessgeLog` | Message log (column spelling matches AD) |
| `Record_ID` | Source Record_ID; on import, filled from an existing PO with the same UUID, otherwise 0 |

---

## 6. ODTPackage.xml format

### 6.1 Location and names

| Context | Path |
|---------|------|
| `Activator` | **Single** file `META-INF/ODTPackage.xml` |
| `ODT2PackActivator` | `META-INF/ODTPackage*.xml` (sorted by file name; stop on first failure) |
| Export Package | Temp file `ODTPackage_{EntityType}.xml`, attached to the package, then deleted |
| Sample | `test/ODTPackage_HelloWorld.xml` (not in `bin.includes`) |

### 6.2 Structure

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

### 6.3 Elements

| Element / attribute | Meaning |
|---------------------|---------|
| `ODT_IDEMPIERE` | Root; may contain several `ODTPackage` nodes |
| `ODTPackage` | `UUID` for package identity; `IsImportedODT=Y` means imported/installed |
| `ODTVersion` | Bundle install requires `VersionNo` **greater than** the installed version; `UUID` for version identity |
| `ODTObjectData` | One AD record; `ObjectData_UUID` finds or creates the PO |
| `ODTObjectDataLine` | One field change |
| `SQL_Apply` | Optional; executed after PO save |
| `SQL_Unapply` | Optional; reserved for uninstall; not executed |
| `AttachmentPayload` | Base64 of attachment ZIP; export sets `encoding="base64"` |
| CDATA | Export sets `xml:space="preserve"` so Transformer indent does not pollute whitespace. Import does **not** trim `NewValue` / `OldValue`. `Name`, `SQL_Apply`, and `SQL_Unapply` **are** trimmed when importing into `KS_ODT*` |

Package and version are looked up by UUID (update if present, insert if not). ObjectData is **always inserted** (not merged by UUID). Re-importing the same Version UUID can duplicate OD rows.

---

## 7. Install pipeline

### 7.1 Three entry points

| Entry | Call chain | Writes KS_ODT* |
|-------|------------|----------------|
| Bundle `Activator` | `ODTVersionCheck` (strict) → `installFromVersionXml` → `importFromXmlNode` | Yes |
| Bundle `ODT2PackActivator` | Same (non-strict; multiple files); then writes Package.EntityType from XML | Yes |
| Process **Package Install** | `MKSODTVersion.packageInstall()` → `installFromVersion` | No (already there) |
| Process **Import Package** + Apply | `importFromXmlNode` → `apply()` | Yes on import; **Apply is a no-op** |

All AD object install logic lives in **`ODTInstallEngine`**.

Context: System Client (0) / System User / System Role. `ODT2PackActivator` also sets System User/Role in `setupPackInContext()`.

### 7.2 Per AD Object

1. `ODTPOHelper.generatePO(uuid, table, trx)` — parameterized UUID lookup or new PO
2. For each ODL, `ODTPOBuilder.buildPO(...)`
3. New `AD_TreeNodeMM`: reload by `AD_Tree_ID + Node_ID`, then set SeqNo / Parent_ID / UUID
4. `ODTPOHelper.reconcileTrlPOIfExistsByNaturalKey(...)` — merge `*_Trl` onto an existing row by parent key + `AD_Language` (and Client)
5. `po.saveEx()`
6. PostgreSQL: `UPDATE` placeholder columns to real `''` (see 7.6)
7. If `AD_Column`: `ODTDdlService.queueColumnSync(...)` (skip view/virtual columns)
8. Run `SQL_Apply` if present
9. After all AD Objects, process `Attachment` rows
10. `ODTPostInstallService.postInstallPackage(...)`
11. Bundle path: `CacheMgt.reset()` then `importFromXmlNode(...)`

### 7.3 Bundle version gate (`ODTVersionCheck`)

| Condition | Activator (`strictDowngrade=true`) | ODT2PackActivator (`false`) |
|-----------|--------------------------------------|-----------------------------|
| `KS_ODTPackage` not in AD | bootstrap install | bootstrap install |
| `VersionNo > installed` | install (does not uninstall the previous version) | install (does not uninstall the previous version) |
| `VersionNo == installed` | **abort entire `install()`** (`return` leaves the loop) | `continue` (skip that package) |
| `VersionNo < installed` | **abort entire `install()`** | treat as already installed |

So if one XML file contains several packages, the first SKIP in `Activator` drops the rest. Merge packages or use `ODT2PackActivator`.

### 7.4 SQL_Apply

- Runs after each ObjectData PO save
- Multiple statements split on `--//--`
- One failed statement is logged at SEVERE; **install continues** (not fail-fast)
- Bundle path uses `trxName=null`; Process path uses the version transaction
- XML install `trim()`s `SQL_Apply` text

### 7.5 ODT2PackActivator phases

```text
preInstallPackage()    # empty hook for subclasses
    ↓
packInAll()
    ├── packInODT()      → META-INF/ODTPackage*.xml
    ├── packIn2Pack()    → META-INF/2Pack_*.zip (Incremental2PackActivator)
    └── packInFolder()   → META-INF/ODT2Pack-*.zip
    ↓
postInstallPackage()   # empty hook
```

`frameworkStarted()`: if iDempiere is already up, run on the thread pool; otherwise listen for `SERVER_START`. ClassLoader is swapped around the run; `ServerContext.dispose()` in `finally`.

### 7.6 Empty strings and mandatory columns

iDempiere `PO` binds `""` as SQL `NULL` on insert. In ODT XML, empty `<NewValue/>` with `IsNewNullValue=false` means “empty string”, not “skip”.

For **new** rows on **mandatory text** columns:

1. `ODTPOBuilder` writes a single space `" "` as a placeholder
2. After successful `saveEx()`, `persistEmptyStrings()` runs `UPDATE ... SET col=''` **on PostgreSQL only**, keyed by UUID
3. Oracle treats `''` as NULL, so that step is skipped

Existing rows are not overwritten with the placeholder, so updates do not wipe optional text.

### 7.7 Skipped / special columns on install

`ODTPOBuilder.buildPO` skips or special-cases:

| Condition | Behavior |
|-----------|----------|
| `AD_Chart_ID` | skip |
| `AD_Client_ID` (non-`_Trl` tables) | skip |
| `Created` / `Updated` / `UpdatedBy` | skip |
| Key column (except `AD_EntityType` and `_Trl`) | skip |
| `C_BPartner` TOTALOPENBALANCE / ACTUALLIFETIMEVALUE / FIRSTSALE / SO_CREDITUSED | skip |
| `AD_User` EMAILVERIFY / EMAILVERIFYDATE / LASTCONTACT / LASTRESULT | skip |
| `AD_Org_ID` (target table is not `AD_Org`) | `setAD_Org_ID(NewID)` |
| `PAttribute` | write `0` |
| Non-updateable column | allowed only for DocumentNo, MovementType, IsSOTrx, IsTransferred, `AD_Tree.TreeType`, `AD_EntityType.EntityType`, `_Trl.AD_Language` |

FK columns prefer `NewUUID` to resolve the target PO; if the UUID is invalid and the column is updateable, the value is set to `null`.

---

## 8. Post-install DDL

The install loop does not call ColumnSync per column. Batch DDL (same idea as 2Pack) is orchestrated by **`ODTPostInstallService.postInstallPackage`**:

```text
ODTDdlService.synchronizeColumns()      → ADD/MODIFY columns (no FK)
        ↓
ODTIndexService.validateTableIndexes()  → TableIndexValidate
        ↓
ODTDdlService.deferForeignKeys()        → deferred FK creation
        ↓
doSequencCheck()                        → Sequence Check (AD_Process_ID=258)
        ↓
doRoleAccessUpdate()                    → Role Access Update (AD_Process_ID=295)
```

The last two processes run as System Client, User 100.

### 8.1 API

```java
ODTPostInstallService.postInstallPackage(ctx);
ODTPostInstallService.postInstallPackage(ctx, entityType, tableIndexUuids, log, trxName);
ODTPostInstallService.postInstallPackage(ctx, entityType, columnIds, tableIndexUuids, log, trxName);
```

| Parameter | Meaning |
|-----------|---------|
| `entityType` | If `tableIndexUuids` is empty, find all `AD_TableIndex` for that EntityType |
| `columnIds` | `AD_Column_ID`s collected by `queueColumnSync` (`LinkedHashSet` preserves order) |
| `tableIndexUuids` | `AD_TableIndex` UUIDs in this version (preferred over EntityType scan) |
| `trxName` | If `null`, each DDL stage opens its own temporary `ODTDDL*` transaction |

### 8.2 Column sync (`ODTDdlService`)

- Skip view columns and virtual columns
- Sort: same table → key columns first → Column_ID
- Create the table if it does not exist, then sync columns
- SysConfig `2PACK_COMMIT_DDL=Y`: `trx.commit()` before and after DDL (same as 2Pack)
- Oracle CLOB/BLOB MODIFY has a special correction
- Column-sync failure throws `AdempiereException` (unlike SQL_Apply, this aborts install)

### 8.3 Index validation (`ODTIndexService`)

Calls `TableIndexValidate.validateTableIndex()`. `IsCreateConstraint=Y` creates UNIQUE / PRIMARY KEY in the database. `TableIndexValidate` requires a non-null `trxName` (managed by `openDdlTrx` / `finishDdlTrx`). Failure throws.

---

## 9. Processes

Classes live in `org.idempiere.myappx.plugin.odt.process`, registered via `@Process` + `ODTProcessFactoryImpl`. Context comes from `getRecord_ID()`.

| Process | Record | Status | Behavior |
|---------|--------|--------|----------|
| **Package Install** | `KS_ODTVersion` | live | `packageInstall()` → `ODTInstallEngine.installFromVersion` |
| **Export Package** | `KS_ODTPackage` | live | Export the **highest VersionNo** as `ODTPackage_{EntityType}.xml` and attach it |
| **Import Package** | (parameters) | live | Parameters `FileName` (file or directory of `.xml`) and `Apply`. Apply calls empty `apply()` |
| **Version Refresh** | `KS_ODTVersion` | live | Rescan AD by Package.EntityType and rebuild OD/ODL |
| **Link Entity Type** | `KS_ODTVersion` | live | Parameter `EntityType` (default `"C"`). Delete existing `AD_EntityType` OD for this version, export that type, rewrite EntityType NewValue on other OD rows, update Package.EntityType |
| **Version Release** | `KS_ODTVersion` | not implemented | Loads the version and returns success; does not change `Version_Status` |
| **Package Uninstall** | `KS_ODTVersion` | not implemented | Loads the version and returns success; does not call `packageUninstal()` |
| **Copy Package** | `KS_ODTPackage` | not implemented | Parameters `KS_ODTPackage_ID` (source), `EntityType`, `IsNewUUID`; returns `Not implemented yet!` |

---

## 10. Core services and helpers

### 10.1 service package

| Class | Role |
|-------|------|
| `ODTInstallEngine` | `installFromVersion` (Process/DB); `installFromVersionXml` (bundle/XML, does not write KS_ODT*) |
| `ODTVersionCheck` | `isOdtRuntimeAvailable`, `resolveInstalledVersionNo`, `evaluate` |

### 10.2 model package — install

| Class | Role |
|-------|------|
| `ODTPOHelper` | `findPO` / `generatePO`; `reconcileTrlPOIfExistsByNaturalKey` |
| `ODTPOBuilder` | `buildPO`; `takeEmptyStringColumns` |
| `ODTDdlService` | `queueColumnSync`, `synchronizeColumns`, `deferForeignKeys`, DDL transactions |
| `ODTIndexService` | `validateTableIndexes`, `collectTableIndexUuidsFromVersion` |
| `ODTPostInstallService` | `postInstallPackage`; `doSequencCheck` / `doRoleAccessUpdate` |

### 10.3 model package — export and XML

| Class | Role |
|-------|------|
| `ODTLookupHelper` | Resolve Table/Search target tables; identifier strings (separator `#@#`) |
| `ODTQueryHelper` | `listPOs`; throws `AdempiereException` on failure |
| `ODTXmlHelper` | CDATA + `xml:space="preserve"`; extract EntityType / TableIndex UUIDs from XML |
| `ODTConstants` | Date pattern `yyyy-MM-dd HH:mm:ss`; `ID_VALUE_SEPARATOR = "#@#"` |

Identifier shape: `ParentIdCol1#@#ParentIdCol2#@#...#@#IdCol1#@#IdCol2`. Document tables (`C_Invoice` / `M_InOut` / `C_Order` / `C_Payment` / `M_Requisition`) keep only `DocumentNo` as identifier.

Lookup resolution order: Table Reference → table named from `*_ID` if it exists → val-rule exceptions when the column name does not match the target table:

| AD_Val_Rule_ID | Table |
|----------------|-------|
| 230 | `C_BPartner` (e.g. `Bill_BPartner_ID`) |
| 231 | `M_Product` |
| 184 | `M_Lot` |
| 272 / 218 | `C_Order` |
| 220 | `C_Invoice` |
| 158 | `AD_Role` |

Val Rule is a UI filter, not a table resolver. Unresolved lookups with a value log WARNING.

Export FK hardcodes:

| Condition | Handling |
|-----------|----------|
| `AD_User` ID = 0 | SuperUser **100** |
| `AD_Menu` ID = -1 | Top menu **0** |
| `AD_Client` ID = 0 | UUID `11237b53-9592-4af1-b3c5-afd216514b5d` |
| `AD_Org` ID = 0 | UUID `3ef41ffc-8ea9-454a-afa2-22949f402ff5` |
| `AD_Menu` ID = 0 | UUID left null (main menu) |
| Identifier: `AD_Org` 0 | `*` |
| Identifier: `AD_User` 0 | `System` |
| Identifier: `AD_Role` 0 | `System Administrator` |

### 10.4 DisplayType coverage

Export (`exportObjectDataLine`) and install (`buildPO`) cover common DisplayTypes. Gaps:

| DisplayType | Notes |
|-------------|-------|
| TableDir / Table / Search / Locator / Location | Supported; FKs export as identifier + NewUUID. Table type hardcodes `AD_Val_Rule_ID` to **0** on both export and install |
| ID | Only `AD_TreeNodeMM.Node_ID/Parent_ID` map to `AD_Menu`; other ID columns log WARNING on export |
| PAttribute | WARNING on export; install writes `0` |
| Integer / Number / Amount / CostPrice / Quantity / Date* / Boolean / String | Supported |

---

## 11. Attachments

`AttachmentODTUtil` handles **process (`AD_Process`)** attachments:

| Direction | Method |
|-----------|--------|
| Export | `exportZipAsBase64(MAttachment)` — `saveAsZip()` then Base64 |
| DB install | `installProcessAttachment(ctx, odtod, trxName, log)` |
| XML/bundle install | `installProcessAttachmentFromXml(ctx, element, log)` |

Refresh writes `ObjectData_Type=Attachment` with `AD_Table_ID` still `AD_Process` and `ObjectData_UUID` equal to the process UUID. Install runs **after** the main ObjectData loop so attachments are not written before process metadata. The process PO is located with `findPO` by UUID.

---

## 12. Version Refresh

`MKSODTVersion.versionRefresh()` is invoked by **Version Refresh**. Package **EntityType is mandatory** (`@FillMandatory@` otherwise).

Filter: `EntityType=? AND AD_Client_ID=0`. `_Trl` rows follow the system-level parent EntityType. `AD_TreeNodeMM`: `Node_ID IN (SELECT AD_Menu_ID FROM AD_Menu WHERE EntityType=? AND AD_Client_ID=0) AND AD_Client_ID=0`.

### 12.1 Steps

1. Parameterized DELETE of ODL and OD where `ObjectData_Type IN ('AD Object','Attachment')` for this version
2. Reset SeqNo counters
3. Export the tables below in order
4. Export process attachments immediately after `AD_Process`

### 12.2 Exported tables (order = SeqNo order)

`AD_EntityType` (fixed SeqNo=10), `AD_SysConfig`, `AD_Message`(+Trl), `AD_Rule`, `AD_Window`(+Trl), `AD_Form`(+Trl), `AD_Element`(+Trl), `AD_Val_Rule`, `AD_Reference`(+Trl), `AD_Process` (then attachments), `AD_Process`(+Trl,+Para,+Para_Trl), `AD_Table`(+Trl), `AD_Column`(+Trl, `AD_Table_ID, isKey desc`), `AD_TableIndex`, `AD_IndexColumn` (`AD_TableIndex_ID, seqNO`), `AD_Ref_Table`, `AD_Tab`(+Trl, `AD_Window_ID, seqNo`), `AD_FieldGroup`(+Trl), `AD_Field`(+Trl, `AD_Tab_ID, seqNo`), `AD_InfoWindow`, `AD_InfoColumn` (`AD_InfoWindow_ID, seqNo`), `AD_Ref_List`(+Trl), `AD_ToolBarButton`, `AD_Workflow`, `AD_WF_Node`, `AD_WF_NodeNext`, `AD_Menu`(+Trl), `AD_TreeNodeMM`

Not covered: e.g. `AD_InfoWindow_Trl`, print formats, reports, most transactional data.

### 12.3 Robustness

| Mechanism | Behavior |
|-----------|----------|
| **UUID check** | `requireObjectUuid()` — missing UUID column, null UUID, or missing FK target throws; **the whole Refresh fails** |
| **Transaction** | OD/ODL use the Process `trxName`; failure can roll back |
| **Visible query failures** | `listPOs` throws; it does not return an empty array silently |
| **Virtual columns** | Skipped on export |

Rows without UUID fail Refresh with table name and Record_ID.

### 12.4 Performance

- `listPOs` with parameterized WHERE
- SeqNo: one `MAX(SeqNo)` per ObjectData class, then +10 in memory; attachments use a separate `allocateAnySeqNo`

---

## 13. 2Pack and ODT2Pack zip

Only **`ODT2PackActivator`** runs these (`Activator.packIn()` is empty).

### 13.1 2Pack_*.zip

Pack In via the iDempiere 2Pack service. Successfully installed packs are recorded as `AD_Package_Imp.Name` + `PK_Status='Completed successfully'` and skipped on later runs. Version strings with more than three segments and `.v` / `.qualifier` are truncated to `major.minor.patch`.

### 13.2 ODT2Pack-*.zip

Name: `ODT2Pack-{major.minor.patch}_{ClientValue}_{Description}.zip`. The version is three numeric segments, for example `ODT2Pack-1.0.0_SYSTEM_MYAPPX.AIC.zip`.

- Within one bundle, install in ascending version order; equal versions then sort by file name
- ClientValue is the second segment; `ALL-CLIENTS` or `ALL-CLIENTS-{seed}` can target many tenants (seed client first)
- A file that does not match the name is skipped
- Missing client / seed fails that zip and stops later zips
- A pack already installed is skipped when the full file name, target client, and `PK_Status='Completed successfully'` match
- After success (ALL-CLIENTS), write `AD_Package_Imp_Proc` / `AD_Package_Imp` to avoid repeats
- Needs a DB lock; may create `MSession` (`WebSession=ODT2PackActivator`) for `AD_ChangeLog`
- Resource is copied to a temp zip, then merge / directMerge

See [2Pack Pack In/Out](https://wiki.idempiere.org/en/Developing_Plug-Ins_-_2Pack_-_Pack_In/Out) and [NF5.1 Automatic External Packin](https://wiki.idempiere.org/en/NF5.1_Automatic_External_Packin).

---

## 14. Typical workflows

### 14.1 Plugin development: ship dictionary in the bundle

1. Maintain AD in iDempiere (EntityType = the plugin’s own entity type)
2. Create `KS_ODTPackage` and set EntityType (or run **Link Entity Type**)
3. **Version Refresh** → **Export Package**
4. Copy the attached XML to that plugin’s `META-INF/ODTPackage.xml` and bump `VersionNo`
5. Plugin Activator extends `ODT2PackActivator` (this ODT bundle must already be deployed)
6. Build and deploy under `myappx-plugins`

### 14.2 Migrate between environments (not bundled)

1. Source: **Version Refresh** → **Export Package**
2. Target: **Import Package** (`Apply=N` writes KS_ODT* only)
3. Target: **Package Install** applies to AD

> Import `Apply=Y` does not install. Run **Package Install** after import.

### 14.3 First deploy of the ODT plugin itself

1. Drop `org.idempiere.myappx.plugin.odt` into plugins and start
2. If the bundle contains `META-INF/ODTPackage.xml`, `Activator` bootstraps ODT’s own dictionary and tables
3. If the XML is not in the bundle: Refresh/Export on a source system, then **Import Package** + **Package Install**, or put the XML in `META-INF` and rebuild
4. Later upgrades: bump XML `VersionNo`; bundle activation installs the new version

### 14.4 Dependent plugins

`ODT2PackActivator` is exported. Dependents should:

1. Require-Bundle `org.idempiere.myappx.plugin.odt`
2. `Activator extends ODT2PackActivator`
3. Ship `META-INF/ODTPackage.xml` (or `ODTPackage*.xml`), optionally `ODT2Pack-*.zip`

---

## 15. Tests and samples

| Resource | Purpose |
|----------|---------|
| `org.idempiere.myappx.plugin.odt/test/ODTPackage_HelloWorld.xml` | Full sample: package **Test HelloWorld**, EntityType **Test_HelloWorld**, `ObjectType=ODT_APP`, VersionNo=1 Draft. Use with Import Package or as an XML reference |

`build.properties` does not include `test/`, so this XML is **not** in the JAR. There is no JUnit / Tycho test module. After a Tycho build, verify Refresh, Install, and bundle install manually in iDempiere.

---

## 16. P2 deployment

1. From the `myappx-plugins` root:

```bash
mvn -f myappx-plugin-odt/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify
```

2. Artifact: `org.idempiere.myappx.plugin.odt.p2/target/repository` (lean: ODT bundle only)

3. Point iDempiere / p2 director at that repository, category **MyAppx ODT Extensions** (`myappx.odt.extensions`). You can also copy `target/*.jar` into the instance `plugins` directory.

4. For **myappx-portable**, prefer OSGi console hot-install (see that repo’s README). If the bundle stays STARTING:

```text
sta org.idempiere.myappx.plugin.odt
```

---

## 17. Logging

`ODTLog` uses prefix `[ODT][Module]` and ` | `-joined `key=value` pairs for grepping.

| Module | Source |
|--------|--------|
| `Activator` | `Activator` |
| `2Pack` | `ODT2PackActivator` |
| `Install` | `ODTInstallEngine` |
| `Export` | Refresh / LinkEntityType |
| `Version` | `ODTVersionCheck` |
| `DDL` / `Index` / `PostInstall` | Post-install |
| `Attachment` / `Lookup` / `PO` / `Query` / `Process` / `Package` | Matching layer |

Set the iDempiere log level to **FINE** to see per-column apply and lookup detail.

---

## 18. Troubleshooting

| Symptom | Likely cause | What to do |
|---------|--------------|------------|
| No ODT windows/tables after bundle start | Missing `META-INF/ODTPackage.xml`; Activator returns immediately | Add the exported XML and rebuild, or Import + Install |
| `Table Name Not Found - KS_ODTPackage` | Version lookup before ODT tables exist | Bootstrap skips that lookup when `KS_ODTPackage` is not yet registered (`ODTVersionCheck`) |
| `TableIndexValidate failed` / `No Transaction Name` | Index validate without trx | Confirm `ODTPostInstallService` + `openDdlTrx` |
| Refresh UUID errors | AD row missing UUID or FK target missing | Add UUIDs or clean invalid data |
| Refresh `listPOs failed` | WHERE or table shape | Check table name and SQL stack in the log |
| Bundle install leaves KS_ODT* empty | `importFromXmlNode` skipped or version gated | Check `VersionNo`; logs `[ODT][Activator]` / `[ODT][Version]` |
| Import does not change AD | Package Install not run; Apply=Y is a no-op | Run **Package Install** |
| `SQL_Apply failed` but install continues | By design | Fix SQL on the ObjectData |
| Multi-package XML installs only the first | Activator `return` on SKIP | Merge packages or use `ODT2PackActivator` |
| Mandatory text NOT NULL failure | Empty `NewValue` treated as NULL | Use `IsNewNullValue=false`; PostgreSQL writes `''` after save |
| `_Trl` PK conflict | Natural key already exists | Engine merges onto the existing row; if it still fails, check Language / parent ID |
| Duplicate OD after re-import | ObjectData always inserts | Delete OD under the version first, or use a new Version UUID |

---

## 19. Known limitations

| Item | Notes |
|------|-------|
| Bundle bootstrap package | `Activator` installs only when `META-INF/ODTPackage.xml` is present; Import + Package Install also works |
| Version upgrade | Installs a higher `VersionNo` only; does not uninstall the previous version; `packageUninstal()` is empty |
| `SQL_Unapply` | Exported and imported; never executed on install or uninstall |
| `MKSODTPackage.apply()` | Empty method; Import `Apply=Y` does not install |
| `CopyPackageProcess` / `VersionReleaseProcess` / `PackageUninstallProcess` | Not implemented |
| `SQL_Apply` failure | Logged; install continues (not fail-fast) |
| DisplayType | Non-TreeNodeMM ID and PAttribute incomplete; Table path Val Rule always 0 |
| Export/install mapping | Separate code paths; round-trip drift is possible |
| ObjectData import | No UUID merge; re-import can duplicate rows |
| `Activator` multi-package | First SKIP aborts the whole file |
| Automated tests | No JUnit/Tycho test module |
| Refresh coverage | Listed AD tables + process attachments only; no print formats, etc. |

---

## 20. Related system configurator

| SysConfig | Meaning |
|-----------|---------|
| `2PACK_COMMIT_DDL` | When `Y`, ODT column sync and index validation commit the transaction before and after DDL (`MSysConfig.TWOPACK_COMMIT_DDL`) |

---

## 21. License

This plugin uses the same license as [iDempiere](https://www.idempiere.org/): [GNU General Public License Version 2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) (GPLv2). See [LICENSE.md](LICENSE.md) for the full text.

Contributor: ken.longnan@gmail.com

*Bundle: `org.idempiere.myappx.plugin.odt`*
