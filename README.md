# Local Object Store (Java)

一个只依赖本地磁盘、不依赖任何外部存储或中间件的对象存储服务。
基于 Spring Boot 3.4 / Java 21，提供内容去重、版本、快照、墓碑删除、空间回收与中断恢复能力。

## 存储与寻址模型

存储根目录由配置项 `objectstore.root` 指定（默认 `data/object-store`），布局如下：

```
<root>/
├── blobs/
│   ├── ab/cd/<sha256>      # 内容寻址 blob：文件名即内容的 sha256，内容不可变
│   └── tmp/<uuid>.tmp      # 写入中的临时文件
└── meta/
    ├── ab/cd/<sha256(key)>.json  # 每个对象键一个元数据文件（key 的 sha256 分片寻址）
    └── tmp/<uuid>.tmp            # 元数据原子替换用临时文件
```

- **内容寻址**：对象内容按 sha256 存为不可变 blob，天然支持跨对象去重——相同内容无论上传多少次、
  被多少个 key 引用，磁盘上只保留一份。
- **元数据与内容分离**：每个 key 的全部版本、当前版本指针、快照列表保存在一个 JSON 文件中。
- **原子提交**：内容先写 `blobs/tmp`，校验通过后 `ATOMIC_MOVE` 到分片目录；
  元数据先写 `meta/tmp` 再原子替换。任何时刻崩溃都不会留下半截对象或半截元数据
  （临时文件在下次启动时统一清理）。
- **上传校验**：上传必须携带 `X-Checksum-SHA256` 头声明 sha256；服务端流式计算实际内容的
  sha256，不一致时拒绝上传（`CHECKSUM_MISMATCH`），并删除临时文件，不产生任何 blob。
- **读时校验**：每次下载都会重新计算 blob 的 sha256 并与元数据比对。
  - 元数据引用的 blob 不存在：`BLOB_MISSING`（悬空引用）
  - blob 内容校验和不匹配：`BLOB_CORRUPT`（磁盘损坏，绝不返回错误数据）

## 版本、快照与删除语义

- **覆盖写即新版本**：对同一 key 的每次成功上传都会追加一个单调递增的版本号；
  历史版本可通过版本列表列举、按版本号读取。
- **回滚**：`rollback` 不修改历史，而是把指定历史版本的内容（或墓碑）复制为一个新版本并移动
  当前指针，因此回滚后所有历史版本仍然可列举、可读取；回滚到墓碑版本会让对象重新变为“已删除”。
- **墓碑删除**：删除追加一个墓碑版本而不是抹掉数据。
  - 读取已删除对象返回 `410 DELETED`；
  - 读取从未存在的对象返回 `404 NOT_FOUND`，两者可明确区分；
  - 删除后可回滚到删除前的版本恢复对象。
- **快照**：快照固定创建时刻的当前版本（记录版本号与 checksum）。快照存活期间，它所引用的
  blob 与版本记录即使不再是当前版本也不会被回收；对象被删除后快照内容依然可读。
  显式释放快照后，引用才允许被回收。

## 空间回收（GC）判定规则

`POST /gc` 触发一次回收，保留期由 `objectstore.retention` 配置（默认 `24h`，支持
 Spring Boot Duration 格式如 `30m`、`PT10S`）。回收分两步：

1. **版本记录裁剪**：某个版本只有同时满足以下条件才会被移除：
   - 不是当前版本（当前版本只有在“过期墓碑且该 key 无任何快照”时才随元数据一起清理）；
   - 不被任何存活快照引用；
   - 版本创建时间早于 `now - retention`。
2. **孤儿 blob 回收**：汇总所有 key 的存活版本与快照引用的 checksum 集合，
   blob 只有同时满足“不在引用集合中”且“最后修改时间早于 `now - retention`”才会被删除。
   崩溃遗留的、从未被元数据引用的 blob 也会在保留期后被清掉，不会成为永久孤儿。

**并发安全**：每个 key 有独立锁串行化写/删/回滚/快照；GC 与“元数据提交”之间由全局读写锁
协调——上传等产生新引用的操作在提交元数据瞬间持有读锁，GC 整体持有写锁，
因此 GC 不可能删除一个元数据已提交引用的 blob，也不会让进行中的读取失败。

## HTTP 接口

对象 key 允许包含 `/`（如 `reports/2026/summary`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/objects/{key}` | 上传/覆盖写，Header `X-Checksum-SHA256` 必填 |
| GET | `/objects/{key}` | 下载当前版本，响应头含 `X-Object-Version`、`X-Checksum-SHA256` |
| DELETE | `/objects/{key}` | 墓碑删除 |
| GET | `/objects/{key}/versions` | 版本列表 |
| GET | `/objects/{key}/versions/{n}` | 下载指定版本 |
| POST | `/objects/{key}/versions/rollback` | 回滚，请求体 `{"version": n}` |
| POST | `/objects/{key}/snapshots` | 创建快照 |
| GET | `/objects/{key}/snapshots` | 快照列表 |
| GET | `/objects/{key}/snapshots/{id}` | 下载快照内容 |
| DELETE | `/objects/{key}/snapshots/{id}` | 释放快照 |
| POST | `/gc` | 触发空间回收，返回裁剪/删除统计 |

错误响应统一为 `{"error": "<错误码>", "message": "..."}`，主要错误码：
`CHECKSUM_REQUIRED` / `CHECKSUM_INVALID` / `CHECKSUM_MISMATCH`（400）、
`NOT_FOUND`（404，从未存在）、`DELETED`（410，墓碑）、
`VERSION_NOT_FOUND` / `SNAPSHOT_NOT_FOUND`（404）、
`BLOB_MISSING` / `BLOB_CORRUPT` / `METADATA_CORRUPT`（500）。

## 本地验证

```bash
# 运行全部测试（沿用工程现有 Maven 构建）
mvn test

# 启动服务（可用 --objectstore.* 覆盖配置）
mvn spring-boot:run
# 或
java -jar target/local-object-store-java-1.0-SNAPSHOT.jar \
  --objectstore.root=/tmp/los/data --objectstore.retention=0s --server.port=8080

# 上传（先算 sha256）
echo -n 'hello-object-store' > v1.bin
CS=$(sha256sum v1.bin | cut -d' ' -f1)
curl -s -X POST -H "X-Checksum-SHA256: $CS" --data-binary @v1.bin \
  http://localhost:8080/objects/reports/summary

# 下载（响应头回传版本号与校验和）
curl -i http://localhost:8080/objects/reports/summary

# 版本列表 / 回滚 / 快照 / 删除 / 回收
curl http://localhost:8080/objects/reports/summary/versions
curl -X POST -H 'Content-Type: application/json' -d '{"version":1}' \
  http://localhost:8080/objects/reports/summary/versions/rollback
curl -X POST http://localhost:8080/objects/reports/summary/snapshots
curl -X DELETE http://localhost:8080/objects/reports/summary
curl -X POST http://localhost:8080/gc
```

## 测试覆盖

测试按业务场景用 `@Nested` 分组，运行时由 `BusinessCaseLoggingExtension`
（通过 JUnit 自动注册）输出形如 `[用例结果][通过] 业务=... 用例=...` 的日志，
可以直接从测试日志看出覆盖了哪些业务及各用例结果：

- 业务1 内容去重与幂等写入：跨 key 去重、校验和缺失/非法/不匹配、16 线程并发同内容只落一份
- 业务2 版本、回滚与删除墓碑：版本列举/读取、回滚恢复、`DELETED` 与 `NOT_FOUND` 区分、回滚到墓碑
- 业务3 快照引用保护：释放前不被 GC 回收、删除后快照仍可读、已删除对象拒绝建快照
- 业务4 空间回收边界：保留期前后、旧版本裁剪、墓碑过期后元数据清理、多键共享 blob 保护
- 业务5 并发一致性：并发写+读校验和始终一致、GC 与写入/回滚/快照交错不误删在用 blob
- 业务6 中断恢复与损坏检测：重启清理临时文件且语义保持、`BLOB_CORRUPT`/`BLOB_MISSING` 可区分、
  崩溃孤儿 blob 可回收
- 业务7 HTTP 全链路接口：真实随机端口上的上传/下载/版本/回滚/快照/删除/GC 与错误码

配置项：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `objectstore.root` | `data/object-store` | 存储根目录 |
| `objectstore.retention` | `24h` | GC 保留期，无引用数据需超过该时长才被回收 |
| `server.port` | `8080` | HTTP 端口 |
