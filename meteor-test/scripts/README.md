# 类型报告

从某一次实际运行的 `interoperability` 目录生成类型矩阵。输入路径必须明确指定，脚本不查找 latest，也不读取协议清单中的历史通过状态。

```bash
python3 meteor-test/scripts/type-matrix-report.py \
  --run-directory build/docker-runs/<docker-run>/meteor-test/build/interoperability/<junit-run> \
  --expectations <reviewed-expectations.json> \
  --output document/testing/METEOR_TYPE_SUPPORT.md
```

Markdown 是面向读者的汇总：按语言和调用方向分节，协议与编码为行、数据类型为列；Java 8 时间单独展示，映射与限制按语言、协议和原因归纳。只显示当前语言的声明类型，无用例的方向和协议用“未验证”说明。表格不逐案列举，也不在每格重复通过数。

同时保存 `METEOR_TYPE_SUPPORT.json` 作为审计数据，包含原始 caseId、运行编号、类型、方向、编码、JUnit XML 位置和逐案状态。`--details-prefix` 可单独指定 JSON 的路径前缀。C 类型使用已签入的 `src/test/resources/matrix/c-type-catalog.json`；`--c-catalog` 可指定审阅过的替代目录。

运行目录必须同时包含 `selected-cases.json`、`summary.json`、`configuration.json` 和 `test-results/TEST-*.xml`。脚本核对运行编号、测试启动状态、缓存复用、计数、全部用例 ID 和 XML 结果；重复、缺失或冲突会报错。定向运行的筛选条件原样显示，不与另一轮拼接。

## 期望数据

[type-matrix-expectations.example.json](type-matrix-expectations.example.json) 只展示结构，所有状态均为未验证。正式期望包含：

- `schemaVersion`：固定为 `1`。
- `review`：`reviewedBy` 和 `basis` 必填，说明谁审查及依据。
- `rules`：按 `select` 选择语言、协议、profile、角色、类型、向量或精确 caseId。字符串支持普通 glob；数组表示其中任一匹配。`caseId` 仅将 `*`、`?` 视为通配符，JUnit 参数方括号按字面匹配。最高 `priority` 必须只有一条规则胜出。
- `support`：`supported`、`mapped`、`limited`、`unsupported`、`unverified`。
- `outcome`：`roundtrip`、`normalization`、`rejection`，默认 `roundtrip`。拒绝和规范化不能宣称无损支持；不支持必须对应精确拒绝。
- `reason`：必填的审查说明。可附 `sdk`、`nativeType`、`wireType`、`encoding`、`transport`。
- `plannedCases`：发布前确定的完整目标清单，每项提供精确 `caseId`、`language`、`protocol`、`profile`、`role`、`type`、`vector`，时间项另设 `section: java-time`。本轮未执行的条目仍保留为 `missing/未验证`。此清单只允许维度字段，禁止携带旧运行的 `runId`、`testStatus`、`support` 或 `junitXml`。
- `caseMappings`：为旧 Date、Decimal、Complex 等没有新显示名的测试补充上述维度；仅映射精确 caseId，不能凭资源文件或同语言其它协议推导支持。
- `axes`：可配置报告中的语言、协议清单及计划 profile。没有相应 SDK 或方向时以文字汇总为未验证；显式计划但未执行的 profile 保留在表中。

没有匹配的审查规则时，即使 JUnit 通过也保持未验证。JUnit 失败始终显示失败；拒绝测试通过只证明约束有效。一个类型有正常范围和拒绝范围时汇总为有限支持，有计划向量未执行时汇总为未验证。

`plannedCases` 为空时，报告明确限定于“本轮实际执行的类型向量”，不宣称覆盖完整范围。Java 自体、Meteor 提供者及消费者分别展示。浏览器不借用 Node 的服务端结果；gRPC-Web text 仍按 Protobuf 消息编码记录。

## 解析验证

仅使用 Python 标准库，不启动 Docker、JVM 或 SDK：

```bash
python3 -m unittest discover -s meteor-test/scripts/tests -p 'test_*.py' -v
```
