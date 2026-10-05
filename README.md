# Local Object Store (Java)

一个零外部依赖的本地对象存储服务：对象数据直接落本地磁盘，通过 HTTP 提供上传、下载、
版本查看、快照、删除与回收能力。基于 Spring Boot 3.4 / Java 21，不依赖任何外部数据库、
对象存储或中间件。

## 1. 存储与寻址模型

存储根目录由配置项 `local-object-store.root` 指定（默认 `./object-store-data`）：

```
object-store-data/
├── blobs/                     # 内容层：内容寻址、不可变
│   └── xx/
│       └── <sha256>           # 文件名即内容的完整 SHA-256，前两位十六进制做分桶
├── tmp/                       # 上传暂存目录（upload-*.tmp），提交成功后原子 rename 到 blobs
└── meta/                      # 元数据层：每个 key / snapshot 一个 JSON 文件
    ├── keys/<encoded-key>.json
    └── snapshots/<snapshot-id>.json
```

**内容寻址 + 去重**：所有写入都先流式计算 SHA-256 并落 `tmp/`，校验通过后用
`ATOMIC_MOVE` 提交到 `blobs/xx/<sha256>`。相同内容（无论是否同一个 key、无论上传多少次）
在磁盘上永远只有一份 blob，元数据只保存对它的引用。

**写 key 编码**：key 使用百分号编码后作为文件名，允许 `/`、中文等任意字符作为对象 key；
HTTP 路径中如包含 `/` 需客户端先做同样编码（简单 key 建议直接用字母数字、`-`、`_`、`.`）。

**幂等写入**：相同内容重复上传返回相同的 checksum、共享同一份 blob；每次上传仍会让该 key
产生一个新版本（记录一次写入事实）。上传时可用请求头 `X-Content-Checksum` 声明校验和
（支持 `sha256:<hex>` 或纯 hex），声明值与实际不符时整个写入被拒绝（HTTP 422），
不留临时对象、不产生版本。

**原子性**：
- blob 提交：同目录临时文件 + `ATOMIC_MOVE`；并发写相同内容时，后提交方发现目标已存在会
  重新校验目标 checksum，结果天然一致，不会重复落盘或产生半写数据。
- 元数据落盘：JSON 序列化到同目录 `meta-*.tmp` 后 `ATOMIC_MOVE` 替换，崩溃后只会看到
  旧版本或新版本，不会出现半截 JSON。
- 上传 body 按原始字节流读取，客户端应发送 `Content-Type: application/octet-stream`，
  避免容器把 `application/x-www-form-urlencoded` 请求体当表单参数提前消费。
- JVM 内同一 key 的写操作由分段锁串行化；跨进程/崩溃安全由上述原子文件操作保证。

## 2. 版本与快照语义

### 版本

- 每次上传生成一个单调递增版本（从 1 开始），记录 `version / checksum / size / timestamp / reason`。
- 覆盖写不覆盖旧数据：新版本指向新 blob（若内容未变则与旧版本共享 blob），旧版本仍可列举、读取。
- 删除是**墓碑（tombstone）语义**：删除操作追加一个特殊墓碑版本并移动当前指针，不删除历史。
  - 读取已删除对象 → HTTP `410 Gone`（`OBJECT_DELETED`）；
  - 读取从未存在的对象 → HTTP `404 Not Found`（`OBJECT_NOT_FOUND`）；
  - 已删除对象的历史版本仍可按版本号读取。
- 回滚：`POST /objects/{key}/versions/{v}/rollback` 仅把当前指针移到一个已存在的**非墓碑**
  版本，不新增、不删除任何版本。回滚到墓碑版本返回 400。回滚后：
  - 当前读取得到被回滚版本的内容；
  - 回滚前的较新版本、墓碑都仍然保留在版本链中，可以继续按版本读取或再次回滚。
- 物理清除：`DELETE /objects/{key}?purgeHistory=true`（仅允许对当前为墓碑的 key 操作）
  删除该 key 的整条版本链；此后内容 blob 是否被回收由引用关系决定（共享 blob 只在最后一个
  引用消失后才会被清理）。

### 快照

- `POST /snapshots` 对当前所有存活对象创建只读快照，记录每个 key 当时的 `(版本号, checksum)`。
- 快照只引用内容、不拷贝内容；快照存活期间它引用的任何 blob 都不会被 GC 回收，
  即使对应 key 已被删除甚至版本链已被清除——快照读取只依赖 blob 本身。
- `DELETE /snapshots/{id}` 释放快照，释放后其引用的 blob 在无其他引用且超过保留期时可回收。

## 3. 回收（GC）判定规则

`POST /gc`（可用 `?retentionSeconds=` 覆盖保留期，默认取
`local-object-store.retention-seconds` = 300 秒）。一个 blob **同时**满足以下条件才会被删除：

1. 不被任何 key 的任何版本引用（历史版本与墓碑前的内容版本都算引用，直到 `purgeHistory`）；
2. 不被任何存活快照引用；
3. 不在进行中的读取集合内（读取期间 blob 被标记为活跃，GC 一律跳过，进行中的读取不会失败）；
4. 最后修改时间早于 `now - 保留期`（防止误删刚提交、元数据尚未引用的内容）。

引用集合的计算与 blob 删除在同一把全局 GC 锁的临界区内完成，与快照创建/释放、
版本写入并发时不会误删在用对象。GC 同时清理 `tmp/` 与 `meta/` 下超过保留期的崩溃残留文件。

## 4. 中断恢复

服务启动（以及 `ObjectStoreService.recover()`）时：

- 清理 `tmp/upload-*.tmp`：写入中途崩溃只可能留下暂存文件，不可能产生半截 blob/对象；
- 清理 `meta/keys`、`meta/snapshots` 下的 `meta-*.tmp`：已提交的 JSON 元数据不受影响。

崩溃窗口分析：
- blob 提交前崩溃 → 只有 tmp 残留，启动即清；
- blob 已提交、元数据未提交崩溃 → 留下一个无引用孤儿 blob，超过保留期后由 GC 回收，
  不会永久残留；
- 元数据已提交 → 引用完整，blob 与元数据自洽。

**损坏检测**：每次读取先对 blob 做一次全量 SHA-256 校验，且返回给客户端的输入流在读完时
再校验一次。blob 缺失或校验和不符会抛出可区分的 `CONTENT_CORRUPTED`（HTTP 409），
绝不会把错误数据当作正常内容返回。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/objects/{key}` | 上传（请求体为原始字节，可选头 `X-Content-Checksum`） |
| GET | `/objects/{key}` | 下载当前版本（响应头 `X-Content-Checksum`、`Content-Length`） |
| GET | `/objects/{key}/versions` | 列出全部版本（含墓碑） |
| GET | `/objects/{key}/versions/{v}` | 下载指定历史版本 |
| POST | `/objects/{key}/versions/{v}/rollback` | 当前指针回滚到指定非墓碑版本 |
| DELETE | `/objects/{key}` | 墓碑删除；加 `?purgeHistory=true` 物理清除版本链 |
| GET | `/objects` | 列出所有 key 的当前状态 |
| POST | `/snapshots` | 创建快照 |
| GET | `/snapshots` / `/snapshots/{id}` | 快照列表 / 快照详情（key→版本+checksum） |
| GET | `/snapshots/{id}/objects/{key}` | 读取快照固定的版本内容 |
| DELETE | `/snapshots/{id}` | 释放快照 |
| POST | `/gc?retentionSeconds=300` | 触发回收，返回引用数、回收前后 blob 数与删除清单 |

错误码：`404 OBJECT_NOT_FOUND / VERSION_NOT_FOUND / SNAPSHOT_NOT_FOUND`、
`410 OBJECT_DELETED`、`422 CHECKSUM_MISMATCH`（含 expected/actual）、
`409 CONTENT_CORRUPTED`。

## 6. 本地验证

环境要求：JDK 21+、Maven 3.9+（仓库已带 Maven Wrapper 配置，直接用 `mvn` 即可）。

```bash
# 运行全部测试（沿用工程现有构建方式）
mvn test

# 启动服务（默认端口 8080，数据目录 ./object-store-data）
mvn spring-boot:run
```

手动验证示例：

```bash
CK=$(printf 'hello object store' | sha256sum | cut -d' ' -f1)

# 上传（带校验和）
printf 'hello object store' > /tmp/greet.bin
curl -sS -X PUT --data-binary @/tmp/greet.bin \
  -H 'Content-Type: application/octet-stream' \
  -H "X-Content-Checksum: $CK" http://localhost:8080/objects/greet

# 下载（对比响应头中的 X-Content-Checksum）
curl -sS -D - http://localhost:8080/objects/greet -o /tmp/greet.bin
sha256sum /tmp/greet.bin

# 版本 / 删除 / 回滚
curl -sS http://localhost:8080/objects/greet/versions
curl -sS -X DELETE http://localhost:8080/objects/greet      # 410 on later GET
curl -sS -X POST http://localhost:8080/objects/greet/versions/1/rollback

# 快照与回收
SNAP=$(curl -sS -X POST http://localhost:8080/snapshots | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
curl -sS -X POST "http://localhost:8080/gc?retentionSeconds=0"
curl -sS "http://localhost:8080/snapshots/$SNAP/objects/greet"
curl -sS -X DELETE "http://localhost:8080/snapshots/$SNAP"
```

测试使用随机端口，数据目录为构建期临时目录（`local-object-store.root` 被覆盖为
`target` 下的临时文件夹），不会污染本地 `object-store-data`。

## 7. 测试覆盖

`mvn test` 控制台按业务分组打印每个用例的开始与 `[OK]` 结果，共 21 个用例：

- **应用启动冒烟**（`ApplicationTests`）：上下文启动 + HTTP 上传/下载闭环。
- **内容去重与幂等写入**（`DeduplicationTests`）：同内容重复上传/跨 key 共享一份 blob、
  错误校验和 422 且不留版本、不同内容各自独立。
- **版本/墓碑/回滚**（`VersionRollbackTests`）：覆盖版本可列举可读取、删除 410 vs 不存在 404、
  回滚后可见性正确且历史不丢失、删除后重写再跨墓碑回滚、禁止回滚墓碑。
- **快照引用保护**（`SnapshotTests`）：快照固定版本不受覆盖/删除/清版本链影响、
  存活快照的 blob 抗 GC、释放后才回收。
- **空间回收边界**（`GarbageCollectionTests`）：超期孤儿回收、保留期窗口内保留、
  被版本引用（含墓碑历史）的 blob 永不回收。
- **并发安全**（`ConcurrencyTests`）：20 线程并发上传相同内容只落一份 blob、版本连续一致；
  并发 GC 不打断进行中的读取。
- **中断恢复与数据自洽**（`RecoveryTests`）：上传/元数据写入中断后重启清理残留、
  基于同磁盘重建服务实例数据自洽、孤儿 blob 最终可回收、损坏内容返回 409 而非错误数据。
