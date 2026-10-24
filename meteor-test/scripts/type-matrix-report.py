#!/usr/bin/env python3
"""Generate a type matrix from one explicitly selected, XML-verified JUnit run.

Only reviewed expectation data determines support semantics. A green rejection
or normalization testcase never becomes a lossless round-trip claim implicitly.
"""
import argparse
from collections import Counter, defaultdict
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

SUPPORT_LABELS = {
    "supported": "支持", "mapped": "映射支持", "limited": "有限支持",
    "unsupported": "不支持", "unverified": "未验证", "failed": "失败",
}
PROTOCOLS = (
    "rsf", "grpc", "grpc-web", "hprose", "soap", "jsonrpc", "xmlrpc",
    "hessian", "connect", "triple", "dubbo", "thrift", "oncrpc",
)
LANGUAGE_LABELS = {
    "java": "Java", "python": "Python", "node": "JavaScript / Node.js",
    "browser": "JavaScript / 浏览器", "go": "Go", "c": "C", "rust": "Rust",
}
TIME_TYPES = (
    "local-date", "local-time", "local-date-time", "instant", "offset-time",
    "offset-date-time", "zoned-date-time", "duration", "period", "year",
    "year-month", "month-day", "zone-id", "zone-offset",
)
TIME_LABELS = {
    "local-date": "LocalDate", "local-time": "LocalTime",
    "local-date-time": "LocalDateTime", "instant": "Instant",
    "offset-time": "OffsetTime", "offset-date-time": "OffsetDateTime",
    "zoned-date-time": "ZonedDateTime", "duration": "Duration", "period": "Period",
    "year": "Year", "year-month": "YearMonth", "month-day": "MonthDay",
    "zone-id": "ZoneId", "zone-offset": "ZoneOffset",
}
METHOD_ROLES = {
    "meteorProviderPreservesPythonNativeType": "provider",
    "meteorConsumerPreservesPythonNativeType": "consumer",
    "meteorProviderAcceptsOneNodeTypeVector": "provider",
    "meteorConsumerAcceptsOneNodeTypeVector": "consumer",
    "meteorProviderAcceptsOnePythonTypeVector": "provider",
    "meteorConsumerAcceptsOnePythonTypeVector": "consumer",
    "meteorProviderAcceptsPythonNativeType": "provider",
    "meteorConsumerReadsPythonNativeType": "consumer",
    "pythonConsumerCallsMeteorProvider": "provider",
    "meteorConsumerCallsPythonProvider": "consumer",
    "pythonConsumerRejectsIntegerOutsideWireRange": "provider",
    "meteorConsumerRejectsIntegerOutsideWireRange": "consumer",
    "providerAcceptsOneNativeGoTypeVector": "provider",
    "consumerReceivesOneNativeGoTypeVector": "consumer",
    "goConsumerPreservesSelectedTypeOnMeteorProvider": "provider",
    "meteorConsumerPreservesSelectedTypeOnGoProvider": "consumer",
    "officialBrowserPreservesDeclaredType": "provider",
    "meteorProviderAcceptsNativeType": "provider",
    "meteorConsumerReadsNativeType": "consumer",
    "meteorConsumerRejectsUnrepresentableType": "consumer",
}
TYPE_CLASSES = {
    "JavaBasicTypeMatrixTest", "JavaTimeTypeMatrixTest", "GoProtoTypeMatrixTest",
    "ThriftGoTypeMatrixTest", "NodeConnectTypeScenarioTest", "BrowserGrpcWebTypeScenarioTest",
    "NodeHproseTypeTest", "NodeJsonRpcTypeTest", "PythonJsonRpcTypeTest",
    "SoapPythonTypeMatrixTest", "XmlRpcPythonTypeMatrixTest", "XmlRpcPythonTypeRejectionTest",
    "PythonGrpcTypeInteropTest", "PythonThriftTypeInteropTest",
    "OncRpcNativeTypeMatrixTest", "OncRpcRejectedTypeMatrixTest",
}
SELECT_FIELDS = {
    "caseId", "className", "method", "language", "protocol", "profile", "role",
    "type", "vector", "discovery", "encoding", "transport", "section",
}
PLANNED_FIELDS = SELECT_FIELDS | {"nativeType", "wireType", "sdk"}



class ReportError(ValueError):
    """Input evidence cannot establish the requested report."""


def load_json(path):
    with Path(path).open(encoding="utf-8") as stream:
        return json.load(stream)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def case_map(entries, source):
    result = {}
    for entry in entries:
        case_id = entry.get("caseId")
        if not isinstance(case_id, str) or not case_id:
            raise ReportError(f"Missing caseId in {source}")
        if case_id in result:
            raise ReportError(f"Duplicate caseId in {source}: {case_id}")
        result[case_id] = entry
    return result


def read_run(directory):
    """Check identities and outcomes against XML copied into this exact run."""
    directory = Path(directory).resolve()
    paths = {name: directory / name for name in (
        "selected-cases.json", "summary.json", "configuration.json",
    )}
    for path in paths.values():
        if not path.is_file():
            raise ReportError(f"Required same-run input is missing: {path}")
    selected = load_json(paths["selected-cases.json"])
    summary = load_json(paths["summary.json"])
    configuration = load_json(paths["configuration.json"])
    if not isinstance(selected, list):
        raise ReportError("selected-cases.json must be a list")
    run_id = summary.get("runId")
    if not run_id or configuration.get("runId") != run_id:
        raise ReportError("summary/configuration runId mismatch")
    if summary.get("testTaskStarted") is not True or summary.get("resultsAvailable") is not True:
        raise ReportError("JUnit did not start or produced no results in this run")
    if summary.get("reusedResults") is not False:
        raise ReportError("Cached/reused results are not fresh type verification")
    actual = case_map(selected, "selected-cases.json")
    counts = Counter(entry.get("status") for entry in selected)
    if set(counts) - {"passed", "failed", "skipped"}:
        raise ReportError("Unknown selected testcase status")
    expected_counts = {"tests": len(selected), **{
        key: counts[key] for key in ("passed", "failed", "skipped")
    }}
    if any(summary.get(key) != value for key, value in expected_counts.items()):
        raise ReportError("summary counts differ from selected cases")

    # Use only the archived XML beside this summary, never a workspace/latest tree.
    xml_cases = []
    xml_files = sorted((directory / "test-results").glob("TEST-*.xml"))
    if not xml_files:
        raise ReportError("No same-run test-results/TEST-*.xml files")
    for xml_file in xml_files:
        suite = ET.parse(xml_file).getroot()
        for testcase in suite.iter("testcase"):
            class_name = testcase.get("classname")
            name = testcase.get("name")
            if not class_name or not name:
                raise ReportError(f"JUnit case has no identity: {xml_file}")
            status = "passed"
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                status = "failed"
            elif testcase.find("skipped") is not None:
                status = "skipped"
            xml_cases.append({
                "caseId": class_name + "#" + name, "status": status,
                "junitXml": str(xml_file),
            })
    archived = case_map(xml_cases, "same-run JUnit XML")
    if set(archived) != set(actual):
        missing = sorted(set(actual) - set(archived))
        extra = sorted(set(archived) - set(actual))
        raise ReportError(f"XML/selected identity mismatch; missing={missing[:3]}, extra={extra[:3]}")
    for case_id, entry in actual.items():
        if entry.get("caseId") != entry.get("className", "") + "#" + entry.get("scenario", ""):
            raise ReportError(f"Selected identity fields disagree: {case_id}")
        if entry["status"] != archived[case_id]["status"]:
            raise ReportError(f"XML/selected status mismatch: {case_id}")
        entry["junitXml"] = archived[case_id]["junitXml"]
    inputs = list(paths.values()) + xml_files
    return {
        "directory": str(directory), "runId": run_id, "summary": summary,
        "configuration": configuration, "cases": actual,
        "inputSha256": {str(path): sha256(path) for path in inputs},
    }


def protocol_of(profile):
    if profile.startswith(("grpcweb", "grpc-web")):
        return "grpc-web"
    for protocol in sorted(PROTOCOLS, key=len, reverse=True):
        if profile == protocol or profile.startswith(protocol + "-"):
            return protocol
    raise ReportError(f"Unknown RPC profile: {profile}")


def profile_details(profile, protocol):
    if protocol == "oncrpc":
        return "XDR", "udp" if "udp" in profile else "tcp"
    if protocol == "thrift":
        encoding = next((value for value in ("binary", "compact", "json") if value in profile), "unspecified")
        return encoding, "http" if "http" in profile else "tcp"
    if "protobuf" in profile or profile.startswith("grpcweb"):
        return "Protobuf", "http"
    if "hessian" in profile:
        return "Hessian2" if "hessian2" in profile or "hessian-2" in profile else "Hessian", (
            "tcp" if protocol == "dubbo" else "http"
        )
    if protocol in ("hprose", "soap", "xmlrpc"):
        return {"hprose": "Hprose", "soap": "XML", "xmlrpc": "XML"}[protocol], "http"
    if profile == "rsf-java":
        return "Java", "tcp"
    return "JSON" if "json" in profile or protocol == "jsonrpc" else "unspecified", (
        "tcp" if protocol in ("rsf", "dubbo") else "http"
    )


def parse_dimensions(case, c_catalog):
    """Read actual expanded display names; semantics still require expectations."""
    scenario = case["scenario"]
    method, separator, parameter = scenario.partition("[")
    class_name = case["className"].rsplit(".", 1)[-1]
    if not separator or not parameter.endswith("]"):
        if class_name in TYPE_CLASSES:
            raise ReportError(f"Unexpanded or initialization type case: {case['caseId']}")
        return None
    parameter = parameter[:-1]
    section = "basic"
    extra = {}
    if parameter.startswith(("java-basic/", "java-time/")):
        match = re.fullmatch(r"java-(basic|time)/self/([^/]+)/([^/]+)/type=([^/]+)/vector=(.+)", parameter)
        if not match:
            raise ReportError(f"Malformed Java type parameters: {parameter}")
        kind, profile, discovery, data_type, vector = match.groups()
        language, role = "java", "self"
        section = "java-time" if kind == "time" else "basic"
    elif parameter.startswith("types/"):
        tokens = parameter.split("/")
        if len(tokens) < 6 or len(tokens) > 8:
            raise ReportError(f"Malformed foreign type parameters: {parameter}")
        _, language, profile, discovery, data_type, vector, *tail = tokens
        language = language.lower()
        if language not in LANGUAGE_LABELS:
            raise ReportError(f"Unknown language in type parameters: {language}")
        role = METHOD_ROLES.get(method)
        for token in tail:
            if token.startswith("meteor-"):
                declared = token.removeprefix("meteor-")
                if role and role != declared:
                    raise ReportError(f"Method/display role mismatch: {case['caseId']}")
                role = declared
            elif token != "rejection":
                raise ReportError(f"Unknown type parameter suffix: {token}")
        if not role:
            raise ReportError(f"No explicit Meteor role mapping: {case['caseId']}")
        if class_name == "GoProtoTypeMatrixTest" and profile in ("grpc", "connect"):
            profile += "-protobuf"
    elif class_name in ("OncRpcNativeTypeMatrixTest", "OncRpcRejectedTypeMatrixTest"):
        match = re.fullmatch(r"(tcp|udp)/(.+)", parameter)
        if not match:
            raise ReportError(f"Malformed C parameters: {parameter}")
        transport, vector = match.groups()
        if vector not in c_catalog:
            raise ReportError(f"C vector missing from reviewed type catalog: {vector}")
        native = c_catalog[vector]
        data_type = native["type"]
        extra = {key: native[key] for key in ("nativeType", "wireType")}
        language, profile, discovery = "c", "oncrpc-xdr-" + transport, "direct"
        role = METHOD_ROLES.get(method)
        if not role:
            raise ReportError(f"No explicit C role mapping: {case['caseId']}")
    else:
        if class_name in TYPE_CLASSES:
            raise ReportError(f"Unknown expanded type case format: {case['caseId']}")
        return None
    profile = profile.lower()
    if role not in ("self", "provider", "consumer"):
        raise ReportError(f"Unknown Meteor role: {case['caseId']}")
    if "rejection" in parameter.split("/") or "RejectedType" in class_name or "RejectionTest" in class_name:
        extra["declaredObservation"] = "rejection"
    if vector == "string-unicode-rejected":
        extra["declaredObservation"] = "rejection"
    protocol = protocol_of(profile)
    encoding, transport = profile_details(profile, protocol)
    return {
        "language": language, "protocol": protocol, "profile": profile,
        "encoding": encoding, "transport": transport, "role": role,
        "discovery": discovery, "section": section, "type": data_type,
        "vector": vector, "method": method, **extra,
    }


def read_expectations(path):
    data = load_json(path)
    review = data.get("review", {})
    if data.get("schemaVersion") != 1 or not review.get("reviewedBy") or not review.get("basis"):
        raise ReportError("Expectations require schemaVersion=1 and review.reviewedBy/basis")
    seen = set()
    for rule in data.get("rules", []):
        rule_id = rule.get("id")
        if not rule_id or rule_id in seen:
            raise ReportError("Expectation rules require unique IDs")
        seen.add(rule_id)
        if not rule.get("select") or set(rule["select"]) - SELECT_FIELDS:
            raise ReportError(f"Unsupported selector in rule: {rule_id}")
        if rule.get("support") not in SUPPORT_LABELS or rule["support"] == "failed":
            raise ReportError(f"Unknown expectation support: {rule_id}")
        if rule.get("outcome", "roundtrip") not in ("roundtrip", "rejection", "normalization"):
            raise ReportError(f"Unknown expectation outcome: {rule_id}")
        if rule["support"] == "unsupported" and rule.get("outcome") != "rejection":
            raise ReportError(f"Unsupported claims require a precise rejection expectation: {rule_id}")
        if not rule.get("reason"):
            raise ReportError(f"Expectation reason is required: {rule_id}")
        if not isinstance(rule.get("priority", 0), int):
            raise ReportError(f"Rule priority must be an integer: {rule_id}")
    case_map(data.get("plannedCases", []), "plannedCases")
    case_map(data.get("caseMappings", []), "caseMappings")
    return data


def matches(rule, case):
    for field, patterns in rule["select"].items():
        patterns = patterns if isinstance(patterns, list) else [patterns]
        if not all(isinstance(pattern, str) for pattern in patterns):
            raise ReportError(f"Selector patterns must be strings: {rule['id']}")
        actual = str(case.get(field, ""))
        if field == "caseId":
            # JUnit parameter brackets are identity text, never glob character classes.
            expressions = [re.escape(pattern).replace(r"\*", ".*").replace(r"\?", ".") for pattern in patterns]
            matched = any(re.fullmatch(expression, actual) is not None for expression in expressions)
        else:
            matched = any(actual == pattern or fnmatch.fnmatchcase(actual, pattern) for pattern in patterns)
        if not matched:
            return False
    return True


def apply_expectation(case, rules):
    candidates = [rule for rule in rules if matches(rule, case)]
    best = None
    if candidates:
        priority = max(rule.get("priority", 0) for rule in candidates)
        winners = [rule for rule in candidates if rule.get("priority", 0) == priority]
        if len(winners) != 1:
            raise ReportError(f"Ambiguous expectation rules for {case['caseId']}")
        best = winners[0]
    if best and case.get("declaredObservation") == "rejection" and best.get("outcome") != "rejection":
        raise ReportError(f"Rejection testcase cannot use a round-trip expectation: {case['caseId']}")
    if best and best.get("outcome") in ("rejection", "normalization"):
        if best["support"] in ("supported", "mapped"):
            raise ReportError(f"A rejection/normalization cannot establish lossless support: {case['caseId']}")
    case["expectedSupport"] = best["support"] if best else "unverified"
    case["outcome"] = best.get("outcome", "roundtrip") if best else "unreviewed"
    case["ruleId"] = best["id"] if best else ""
    case["reason"] = best["reason"] if best else "该精确场景没有经审查的支持语义。"
    for field in ("nativeType", "wireType", "sdk", "encoding", "transport"):
        if best and field in best:
            case[field] = best[field]
    if case["testStatus"] == "failed":
        case["support"] = "failed"
    elif case["testStatus"] != "passed":
        case["support"] = "unverified"
    else:
        case["support"] = case["expectedSupport"]
    return case


def planned_dimensions(item):
    forbidden = set(item) - PLANNED_FIELDS
    if forbidden:
        raise ReportError("Planned case contains non-dimension/execution fields: " + ", ".join(sorted(forbidden)))
    return dict(item)


def complete_dimensions(entry):
    encoding, transport = profile_details(entry["profile"], entry["protocol"])
    entry.setdefault("encoding", encoding)
    entry.setdefault("transport", transport)
    entry.setdefault("discovery", "direct")
    default_section = "java-time" if entry.get("language") == "java" and entry["type"] in TIME_TYPES else "basic"
    entry.setdefault("section", default_section)
    if default_section == "java-time" and entry["section"] != "java-time":
        raise ReportError("Java time type cannot be hidden in a basic section: " + entry["caseId"])
    return entry


def build_details(run, expectations, c_catalog):
    overrides = case_map(expectations.get("caseMappings", []), "caseMappings")
    planned = case_map(expectations.get("plannedCases", []), "plannedCases")
    for item in list(overrides.values()) + list(planned.values()):
        planned_dimensions(item)
    result = {}
    for case_id, actual in run["cases"].items():
        dimensions = parse_dimensions(actual, c_catalog)
        explicit = overrides.get(case_id) or planned.get(case_id)
        if explicit:
            supplied = planned_dimensions(explicit)
            identities = {"className": actual["className"], "method": actual["scenario"].partition("[")[0]}
            for key, value in identities.items():
                if key in supplied and supplied[key] != value:
                    raise ReportError(f"Explicit mapping contradicts actual identity {key}: {case_id}")
            if dimensions:
                for key in ("language", "protocol", "profile", "role", "type", "vector"):
                    if key in supplied and supplied[key] != dimensions[key]:
                        raise ReportError(f"Explicit mapping contradicts display {key}: {case_id}")
            dimensions = {**(dimensions or {}), **supplied}
        if dimensions is None:
            continue
        required = ("language", "protocol", "profile", "role", "type", "vector")
        if any(not dimensions.get(key) for key in required):
            raise ReportError(f"Incomplete type dimensions: {case_id}")
        entry = {
            "runId": run["runId"], "caseId": case_id, "className": actual["className"],
            "method": actual["scenario"].partition("[")[0],
            "testStatus": actual["status"], "seconds": actual.get("seconds", ""),
            "failure": actual.get("failure", ""), "junitXml": actual["junitXml"],
            **dimensions,
        }
        result[case_id] = apply_expectation(complete_dimensions(entry), expectations.get("rules", []))

    # A planned but unexecuted vector remains visible as missing, even if its
    # neighboring vectors passed. Never use another SDK or another run to fill it.
    for case_id, item in planned.items():
        if case_id in result:
            continue
        if case_id in run["cases"]:
            raise ReportError(f"Planned actual case was not classified: {case_id}")
        entry = {
            **planned_dimensions(item),
            "runId": run["runId"], "className": case_id.partition("#")[0],
            "method": case_id.partition("#")[2].partition("[")[0],
            "testStatus": "missing", "junitXml": "",
        }
        for key in ("language", "protocol", "profile", "role", "type", "vector"):
            if not entry.get(key):
                raise ReportError(f"Incomplete planned case {key}: {case_id}")
        result[case_id] = apply_expectation(complete_dimensions(entry), expectations.get("rules", []))
    return sorted(result.values(), key=lambda entry: entry["caseId"])


def aggregate(entries):
    if not entries:
        return {"support": "unverified", "passed": 0, "total": 0, "missing": 0, "skipped": 0}
    statuses = {entry["support"] for entry in entries}
    if "failed" in statuses:
        support = "failed"
    elif "unverified" in statuses:
        support = "unverified"
    elif statuses == {"unsupported"}:
        support = "unsupported"
    elif "limited" in statuses or "unsupported" in statuses:
        support = "limited"
    elif "mapped" in statuses:
        support = "mapped"
    else:
        support = "supported"
    return {
        "support": support, "passed": sum(entry["testStatus"] == "passed" for entry in entries),
        "total": len(entries), "missing": sum(entry["testStatus"] == "missing" for entry in entries),
        "skipped": sum(entry["testStatus"] == "skipped" for entry in entries),
    }


def escape(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def markdown_table(headers, rows):
    lines = ["| " + " | ".join(map(escape, headers)) + " |"]
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    lines.extend("| " + " | ".join(map(escape, row)) + " |" for row in rows)
    return "\n".join(lines)


def cell_label(entries):
    return SUPPORT_LABELS[aggregate(entries)["support"]]


def profiles_for(entries, axes, language, role):
    profiles = {
        (entry["protocol"], entry["profile"], entry.get("encoding", "unspecified"))
        for entry in entries
    }
    for planned in axes.get("profiles", []):
        selected_language = planned.get("language") in (None, language)
        selected_role = planned.get("role") in (None, role)
        if selected_language and selected_role:
            encoding, _ = profile_details(planned["profile"], planned["protocol"])
            profiles.add((planned["protocol"], planned["profile"], planned.get("encoding", encoding)))
    return sorted(profiles)


def type_groups(entries, language):
    """Keep every declared type, without columns borrowed from other languages."""
    actual = {entry["type"] for entry in entries if entry.get("section") != "java-time"}
    if language == "java":
        groups = [
            ("基础类型", ["boolean", "byte", "short", "int", "long", "float", "double", "char"]),
            ("包装类型", ["boxed-boolean", "boxed-byte", "boxed-short", "boxed-int", "boxed-long",
                          "boxed-float", "boxed-double", "boxed-char"]),
            ("文本、二进制及常见值", ["string", "bytes", "big-integer", "big-decimal", "uuid", "date"]),
            ("集合与结构", ["int-array", "string-array", "list-string", "set-string", "map-string-int", "dto"]),
        ]
    else:
        groups = [
            ("布尔与有符号整数", ["boolean", "int8", "int16", "int32", "int64", "signed-char", "short", "long"]),
            ("无符号整数与浮点数", ["uint8", "uint16", "uint32", "uint64", "float32", "float64",
                                    "unsigned-char", "unsigned-short", "unsigned-long"]),
            ("文本、二进制与空值", ["text", "string", "binary", "fixed-binary", "enum", "optional"]),
            ("容器与结构", ["list", "map", "record", "array", "fixed-array", "union", "optional-struct"]),
        ]
    assigned = set()
    selected = []
    for title, types in groups:
        present = [name for name in types if name in actual]
        assigned.update(present)
        # A small table remains readable in the repository's Markdown viewer.
        for offset in range(0, len(present), 8):
            suffix = "（续）" if offset else ""
            selected.append((title + suffix, present[offset:offset + 8]))
    remaining = sorted(actual - assigned)
    for offset in range(0, len(remaining), 8):
        selected.append(("其它声明类型", remaining[offset:offset + 8]))
    return selected


def type_label(name):
    labels = {
        "boxed-boolean": "Boolean", "boxed-byte": "Byte", "boxed-short": "Short",
        "boxed-int": "Integer", "boxed-long": "Long", "boxed-float": "Float",
        "boxed-double": "Double", "boxed-char": "Character", "bytes": "byte[]",
        "big-integer": "BigInteger", "big-decimal": "BigDecimal", "uuid": "UUID",
        "date": "Date", "int-array": "int[]", "string-array": "String[]",
        "list-string": "List<String>", "set-string": "Set<String>",
        "map-string-int": "Map<String,Integer>", "dto": "DTO",
    }
    return TIME_LABELS.get(name, labels.get(name, name))


def profile_label(profile):
    protocol, name, encoding = profile
    # Profile is retained because custom JSON, Protobuf and framing modes differ.
    return f"{name} / {encoding}"


def matrix_table(entries, profiles, data_types):
    rows = []
    for profile in profiles:
        protocol, name, encoding = profile
        selected = [entry for entry in entries if entry["protocol"] == protocol
                    and entry["profile"] == name and entry.get("encoding", "unspecified") == encoding]
        rows.append([profile_label(profile)] + [
            cell_label([entry for entry in selected if entry["type"] == name]) for name in data_types
        ])
    return markdown_table(["协议 / 编码"] + [type_label(name) for name in data_types], rows)


def limitation_reason(entry):
    reason = entry["reason"]
    if entry["language"] == "node" and entry["protocol"] == "jsonrpc" and reason.startswith("固定 Jayson 数字词法"):
        # Lexeme-specific expected values remain in JSON; the matrix explains
        # the common SDK boundary once, rather than repeating each vector.
        return ("Jayson 将整数解析为 Number；超过安全整数范围会舍入。Meteor 提供者按舍入后的数字词法接收，"
                "超出 Java long 范围时返回 Invalid params；消费者方向由测试业务 guard 明确拒绝不安全整数。")
    return reason


def render_markdown(report, details_path, report_directory=None):
    def detail_link(path):
        if report_directory is None:
            return path.name
        try:
            return Path(os.path.relpath(path.resolve(), Path(report_directory).resolve())).as_posix()
        except ValueError:
            return path.resolve().as_posix()

    cases = report["cases"]
    run = report["run"]
    axes = report.get("axes", {})
    languages = axes.get("languages", list(LANGUAGE_LABELS))
    protocols = axes.get("protocols", list(PROTOCOLS))
    counts = report["typeCaseCounts"]
    configuration = run["configuration"]
    platform = configuration.get("executionPlatform", {})
    scope = "显式计划的类型用例" if report["hasPlannedCases"] else "本轮实际执行的类型向量"
    lines = [
        "# 跨语言类型支持矩阵", "",
        f"按语言和调用方向分节，行表示协议与编码配置，列表示数据类型。支持结论限定于{scope}。", "",
        "支持：所列向量保真往返；映射支持：经明确的线格式转换保留值；有限支持：存在范围、精度或规范化限制；"
        "不支持：已验证精确拒绝；未验证：本轮无证据；失败：验证结果与期望不符。", "",
        f"类型用例 {len(cases)} 项：通过 {counts.get('passed', 0)}，失败 {counts.get('failed', 0)}，"
        f"跳过 {counts.get('skipped', 0)}，未执行 {counts.get('missing', 0)}。"
        "通过数包含精确拒绝和规范化测试，不能等同于无损支持。", "",
        "## 验证范围", "",
        markdown_table(["项目", "本轮证据"], [
            ["运行编号", run["runId"]],
            ["执行环境", " / ".join(str(platform.get(name, "未记录")) for name in ("os", "architecture", "javaVersion"))],
            ["测试筛选", json.dumps(configuration.get("includes", []), ensure_ascii=False)],
            ["矩阵筛选", json.dumps(configuration.get("matrixSelection", {}), ensure_ascii=False)],
            ["审计数据", f"[JSON]({detail_link(details_path)})，保存运行路径、SDK、逐案结果和审查依据"],
        ]), "",
        "类型矩阵覆盖本轮直连场景，不推导所有类型与 TLS、注册发现的全排列。"
        "Java 自体用例检查 DTO 中的实际声明字段，不推导根值调用或任意 Object 多态映射。", "",
    ]
    roles = {"self": "Java 自体", "provider": "Meteor 作为提供者", "consumer": "Meteor 作为消费者"}
    for language in languages:
        language_cases = [entry for entry in cases if entry["language"] == language]
        lines += ["## " + LANGUAGE_LABELS.get(language, language), ""]
        if not language_cases and not any(item.get("language") in (None, language)
                                          for item in axes.get("profiles", [])):
            lines += ["未验证：本轮没有该语言的类型用例，不借用其它语言或 SDK 的结果。", ""]
            continue
        role_names = ["self", "provider", "consumer"] if language == "java" else ["provider", "consumer"]
        for role in role_names:
            role_cases = [entry for entry in language_cases if entry["role"] == role]
            profiles = profiles_for(role_cases, axes, language, role)
            lines += ["### " + roles[role], ""]
            if not profiles:
                lines += ["未验证：本轮没有该调用方向的类型用例。", ""]
                continue
            missing_protocols = [name for name in protocols if name not in {item[0] for item in profiles}]
            if missing_protocols:
                lines += ["未验证协议：" + "、".join(missing_protocols) + "。", ""]
            for title, data_types in type_groups(role_cases, language):
                lines += ["**" + title + "**", "", matrix_table(role_cases, profiles, data_types), ""]
            time_cases = [entry for entry in role_cases if entry.get("section") == "java-time"]
            if language == "java" and (time_cases or role == "self"):
                lines += ["#### Java 8 时间", ""]
                selected_types = [name for name in TIME_TYPES if any(entry["type"] == name for entry in time_cases)]
                if not selected_types:
                    selected_types = list(TIME_TYPES)
                for offset in range(0, len(selected_types), 7):
                    lines += [matrix_table(time_cases, profiles, selected_types[offset:offset + 7]), ""]

    # Human-readable causes are shared across directions and equivalent profiles;
    # per-vector rule IDs and exact literals belong to the audit JSON only.
    reviewed = defaultdict(list)
    for entry in cases:
        if entry.get("ruleId") and entry["support"] != "supported":
            reviewed[(entry["language"], entry["protocol"], limitation_reason(entry))].append(entry)
    lines += ["## 映射与限制", ""]
    rows = []
    for (language, protocol, reason), entries in sorted(reviewed.items()):
        support = "、".join(SUPPORT_LABELS[name] for name in SUPPORT_LABELS
                           if name in {entry["support"] for entry in entries})
        types = "、".join(type_label(name) for name in sorted({entry["type"] for entry in entries}))
        rows.append([LANGUAGE_LABELS.get(language, language), protocol, support, types, reason])
    if rows:
        lines += [markdown_table(["语言", "协议", "支持程度", "数据类型", "映射或限制"], rows), ""]
    else:
        lines += ["本轮没有已审查的映射、限制或拒绝项。", ""]
    return "\n".join(lines)


def generate(run_directory, expectations_path, c_catalog_path, output, details_prefix=None):
    run = read_run(run_directory)
    expectations = read_expectations(expectations_path)
    c_catalog = {}
    if c_catalog_path and Path(c_catalog_path).is_file():
        catalog = load_json(c_catalog_path)
        if catalog.get("schemaVersion") != 1:
            raise ReportError("C catalog requires schemaVersion=1")
        for entry in catalog.get("vectors", []):
            if entry["vector"] in c_catalog:
                raise ReportError("Duplicate C vector: " + entry["vector"])
            c_catalog[entry["vector"]] = entry
    cases = build_details(run, expectations, c_catalog)
    if not cases:
        raise ReportError("The explicit run contains no recognized or explicitly mapped type cases")
    run_metadata = {key: value for key, value in run.items() if key != "cases"}
    report = {
        "schemaVersion": 1, "run": run_metadata, "review": expectations["review"],
        "expectationsFile": str(Path(expectations_path).resolve()),
        "expectationsSha256": sha256(expectations_path),
        "cCatalogSha256": sha256(c_catalog_path) if c_catalog else None,
        "hasPlannedCases": bool(expectations.get("plannedCases")),
        "axes": expectations.get("axes", {}),
        "typeCaseCounts": dict(Counter(entry["testStatus"] for entry in cases)),
        "supportCounts": dict(Counter(entry["support"] for entry in cases)), "cases": cases,
    }
    output = Path(output)
    prefix = Path(details_prefix) if details_prefix else output.with_suffix("")
    details_path = prefix.with_suffix(".json")
    for path in (output, details_path):
        path.parent.mkdir(parents=True, exist_ok=True)
    details_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output.write_text(render_markdown(report, details_path, output.parent), encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-directory", required=True, type=Path)
    parser.add_argument("--expectations", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--details-prefix", type=Path)
    default_catalog = Path(__file__).resolve().parents[1] / "src/test/resources/matrix/c-type-catalog.json"
    parser.add_argument("--c-catalog", type=Path, default=default_catalog)
    args = parser.parse_args(argv)
    try:
        report = generate(args.run_directory, args.expectations, args.c_catalog, args.output, args.details_prefix)
    except (ReportError, OSError, json.JSONDecodeError, ET.ParseError) as error:
        parser.error(str(error))
    print(json.dumps({
        "runId": report["run"]["runId"], "typeCases": len(report["cases"]),
        "testStatus": report["typeCaseCounts"], "support": report["supportCounts"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
