"""Pure parsing/evidence tests; no Docker, JVM or SDK is started."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

MODULE_PATH = Path(__file__).resolve().parents[1] / "type-matrix-report.py"
SPEC = importlib.util.spec_from_file_location("type_matrix_report", MODULE_PATH)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def scenario(class_name, method, parameters):
    name = method + "[" + parameters + "]"
    return {"caseId": class_name + "#" + name, "className": class_name, "scenario": name}


def example_case():
    return scenario(
        "net.hasor.realmeteor.protocol.grpc.python.PythonGrpcTypeInteropTest",
        "meteorProviderPreservesPythonNativeType",
        "types/python/grpc-protobuf/direct/int64/int64-maximum",
    )


def rule(support="supported", outcome="roundtrip"):
    return {
        "id": "reviewed-example", "select": {"language": "python"},
        "support": support, "outcome": outcome, "reason": "One reviewed test fixture.",
    }


class TypeMatrixParserTest(unittest.TestCase):
    def test_java_basic_display_preserves_boxed_type(self):
        # Read the exact Java @Parameters layout, including explicit type/vector.
        case = scenario("net.hasor.realmeteor.matrix.types.JavaBasicTypeMatrixTest", "echo",
                        "java-basic/self/rsf-java/direct/type=boxed-char/vector=unicode-cjk")
        value = REPORT.parse_dimensions(case, {})
        self.assertEqual(("java", "self", "boxed-char", "rsf-java"),
                         (value["language"], value["role"], value["type"], value["profile"]))

    def test_java_time_is_separate_from_legacy_date(self):
        case = scenario("net.hasor.realmeteor.matrix.types.JavaTimeTypeMatrixTest", "echo",
                        "java-time/self/grpc-protobuf/direct/type=zoned-date-time/vector=utc-region-identity")
        self.assertEqual("java-time", REPORT.parse_dimensions(case, {})["section"])

    def test_python_provider_role_is_meteor_provider(self):
        self.assertEqual("provider", REPORT.parse_dimensions(example_case(), {})["role"])

    def test_python_consumer_role_is_meteor_consumer(self):
        case = scenario("x.PythonGrpcTypeInteropTest", "meteorConsumerPreservesPythonNativeType",
                        "types/python/grpc-protobuf/direct/int64/int64-maximum")
        self.assertEqual("consumer", REPORT.parse_dimensions(case, {})["role"])

    def test_go_uppercase_display_has_canonical_language(self):
        case = scenario("x.GoProtoTypeMatrixTest", "providerAcceptsOneNativeGoTypeVector",
                        "types/Go/grpc/direct/uint64/uint64-maximum")
        value = REPORT.parse_dimensions(case, {})
        self.assertEqual(("go", "grpc-protobuf", "Protobuf"),
                         (value["language"], value["profile"], value["encoding"]))

    def test_node_connect_display_provides_its_role(self):
        case = scenario("x.NodeConnectTypeScenarioTest", "preservesDeclaredTypeAcrossOfficialNodeSdk",
                        "types/node/connect-Json/direct/binary/binary-empty/meteor-consumer")
        self.assertEqual("consumer", REPORT.parse_dimensions(case, {})["role"])

    def test_browser_text_is_protobuf_not_json(self):
        case = scenario("x.BrowserGrpcWebTypeScenarioTest", "officialBrowserPreservesDeclaredType",
                        "types/browser/grpcweb-text/direct/binary/binary-empty/meteor-provider")
        value = REPORT.parse_dimensions(case, {})
        self.assertEqual(("grpc-web", "Protobuf", "provider"),
                         (value["protocol"], value["encoding"], value["role"]))

    def test_display_cannot_contradict_direction_method(self):
        case = scenario("x.BrowserGrpcWebTypeScenarioTest", "officialBrowserPreservesDeclaredType",
                        "types/browser/grpcweb-text/direct/binary/binary-empty/meteor-consumer")
        with self.assertRaisesRegex(REPORT.ReportError, "role mismatch"):
            REPORT.parse_dimensions(case, {})

    def test_c_catalog_preserves_native_long_mapping(self):
        case = scenario("x.OncRpcNativeTypeMatrixTest", "meteorProviderAcceptsNativeType", "udp/i64-long-min")
        catalog = {"i64-long-min": {"type": "long", "nativeType": "C long (LP64)", "wireType": "XDR hyper"}}
        value = REPORT.parse_dimensions(case, catalog)
        self.assertEqual(("long", "C long (LP64)", "oncrpc-xdr-udp"),
                         (value["type"], value["nativeType"], value["profile"]))

    def test_c_vector_without_catalog_is_rejected(self):
        case = scenario("x.OncRpcNativeTypeMatrixTest", "meteorProviderAcceptsNativeType", "tcp/i64-long-min")
        with self.assertRaisesRegex(REPORT.ReportError, "missing from"):
            REPORT.parse_dimensions(case, {})

    def test_initialization_error_is_not_an_ignored_type_case(self):
        case = {"caseId": "x.JavaBasicTypeMatrixTest#initializationError", "className": "x.JavaBasicTypeMatrixTest",
                "scenario": "initializationError"}
        with self.assertRaisesRegex(REPORT.ReportError, "initialization"):
            REPORT.parse_dimensions(case, {})


class TypeMatrixSupportTest(unittest.TestCase):
    def entry(self, status="passed"):
        return {"caseId": "a#b", "language": "python", "testStatus": status}

    def test_unreviewed_green_case_stays_unverified(self):
        self.assertEqual("unverified", REPORT.apply_expectation(self.entry(), [])["support"])

    def test_reviewed_round_trip_can_establish_support(self):
        self.assertEqual("supported", REPORT.apply_expectation(self.entry(), [rule()])["support"])

    def test_passed_rejection_remains_unsupported(self):
        value = REPORT.apply_expectation(self.entry(), [rule("unsupported", "rejection")])
        self.assertEqual(("unsupported", "passed"), (value["support"], value["testStatus"]))

    def test_passed_normalization_remains_limited(self):
        actual = REPORT.apply_expectation(self.entry(), [rule("limited", "normalization")])
        self.assertEqual("limited", actual["support"])

    def test_failure_cannot_be_reclassified_as_unsupported(self):
        actual = REPORT.apply_expectation(self.entry("failed"), [rule("unsupported", "rejection")])
        self.assertEqual("failed", actual["support"])

    def test_declared_rejection_rejects_global_round_trip_rule(self):
        entry = {**self.entry(), "declaredObservation": "rejection"}
        with self.assertRaisesRegex(REPORT.ReportError, "Rejection testcase"):
            REPORT.apply_expectation(entry, [rule()])

    def test_normalization_cannot_be_claimed_as_lossless(self):
        with self.assertRaisesRegex(REPORT.ReportError, "cannot establish"):
            REPORT.apply_expectation(self.entry(), [rule("supported", "normalization")])

    def test_ambiguous_equal_priority_rules_are_rejected(self):
        second = {**rule(), "id": "another-review"}
        with self.assertRaisesRegex(REPORT.ReportError, "Ambiguous"):
            REPORT.apply_expectation(self.entry(), [rule(), second])

    def test_explicit_higher_priority_rule_wins(self):
        second = {**rule("limited", "normalization"), "id": "specific-review", "priority": 10}
        self.assertEqual("limited", REPORT.apply_expectation(self.entry(), [rule(), second])["support"])

    def test_exact_case_id_with_parameter_brackets_matches_itself(self):
        entry = {**self.entry(), "caseId": example_case()["caseId"]}
        exact = {**rule(), "select": {"caseId": entry["caseId"]}}
        self.assertEqual("supported", REPORT.apply_expectation(entry, [exact])["support"])

    def test_case_id_wildcard_keeps_parameter_brackets_literal(self):
        entry = {**self.entry(), "caseId": example_case()["caseId"]}
        exact = {**rule(), "select": {"caseId": "*#meteorProvider*[types/python/*/int64/int64-maximum]"}}
        self.assertEqual("supported", REPORT.apply_expectation(entry, [exact])["support"])

    def test_partial_vector_set_is_unverified(self):
        entries = [{"support": "supported", "testStatus": "passed"},
                   {"support": "unverified", "testStatus": "missing"}]
        self.assertEqual("unverified", REPORT.aggregate(entries)["support"])

    def test_mixed_valid_and_rejected_ranges_are_limited(self):
        entries = [{"support": "supported", "testStatus": "passed"},
                   {"support": "unsupported", "testStatus": "passed"}]
        self.assertEqual("limited", REPORT.aggregate(entries)["support"])


class TypeMatrixEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "run-A"
        self.path.mkdir()
        self.case = {**example_case(), "status": "passed", "seconds": "0.123"}
        self.write_run([self.case])

    def write_run(self, cases):
        summary = {"runId": "run-A", "tests": len(cases), "passed": len(cases), "failed": 0,
                   "skipped": 0, "testTaskStarted": True, "resultsAvailable": True, "reusedResults": False}
        (self.path / "selected-cases.json").write_text(json.dumps(cases))
        (self.path / "summary.json").write_text(json.dumps(summary))
        (self.path / "configuration.json").write_text(json.dumps({"runId": "run-A"}))
        results = self.path / "test-results"
        results.mkdir(exist_ok=True)
        suite = ET.Element("testsuite")
        for item in cases:
            ET.SubElement(suite, "testcase", classname=item["className"], name=item["scenario"])
        ET.ElementTree(suite).write(results / "TEST-fixture.xml")

    def test_same_run_xml_and_selected_cases_are_accepted(self):
        self.assertEqual(1, len(REPORT.read_run(self.path)["cases"]))

    def test_duplicate_selected_id_is_rejected(self):
        self.write_run([self.case, self.case])
        with self.assertRaisesRegex(REPORT.ReportError, "Duplicate caseId"):
            REPORT.read_run(self.path)

    def test_another_run_configuration_is_rejected(self):
        (self.path / "configuration.json").write_text(json.dumps({"runId": "run-B"}))
        with self.assertRaisesRegex(REPORT.ReportError, "runId mismatch"):
            REPORT.read_run(self.path)

    def test_cached_report_is_rejected(self):
        data = REPORT.load_json(self.path / "summary.json")
        data["reusedResults"] = True
        (self.path / "summary.json").write_text(json.dumps(data))
        with self.assertRaisesRegex(REPORT.ReportError, "Cached"):
            REPORT.read_run(self.path)

    def test_startup_failure_does_not_accept_old_xml(self):
        data = REPORT.load_json(self.path / "summary.json")
        data["testTaskStarted"] = False
        (self.path / "summary.json").write_text(json.dumps(data))
        with self.assertRaisesRegex(REPORT.ReportError, "did not start"):
            REPORT.read_run(self.path)

    def test_summary_counts_must_match_selected_cases(self):
        data = REPORT.load_json(self.path / "summary.json")
        data["passed"] = 2
        (self.path / "summary.json").write_text(json.dumps(data))
        with self.assertRaisesRegex(REPORT.ReportError, "summary counts"):
            REPORT.read_run(self.path)

    def test_selected_and_xml_status_must_match(self):
        xml_file = self.path / "test-results/TEST-fixture.xml"
        tree = ET.parse(xml_file)
        ET.SubElement(tree.getroot().find("testcase"), "failure").text = "failure"
        tree.write(xml_file)
        with self.assertRaisesRegex(REPORT.ReportError, "status mismatch"):
            REPORT.read_run(self.path)

    def test_extra_xml_case_is_not_silently_imported(self):
        xml_file = self.path / "test-results/TEST-fixture.xml"
        tree = ET.parse(xml_file)
        ET.SubElement(tree.getroot(), "testcase", classname="old.Case", name="oldTest")
        tree.write(xml_file)
        with self.assertRaisesRegex(REPORT.ReportError, "identity mismatch"):
            REPORT.read_run(self.path)

    def test_planned_missing_vector_is_retained(self):
        run = REPORT.read_run(self.path)
        item = {**REPORT.parse_dimensions(self.case, {}), "caseId": "future.Case#missing"}
        expectations = {"plannedCases": [item], "rules": [rule()]}
        details = REPORT.build_details(run, expectations, {})
        self.assertEqual(["missing", "passed"], sorted(entry["testStatus"] for entry in details))

    def test_old_execution_fields_cannot_turn_missing_plan_into_support(self):
        run = REPORT.read_run(self.path)
        item = {**REPORT.parse_dimensions(self.case, {}), "caseId": "future.Case#missing",
                "testStatus": "passed", "runId": "WRONG-RUN"}
        with self.assertRaisesRegex(REPORT.ReportError, "execution fields"):
            REPORT.build_details(run, {"plannedCases": [item], "rules": [rule()]}, {})

    def test_explicit_mapping_cannot_replace_actual_class_identity(self):
        run = REPORT.read_run(self.path)
        item = {"caseId": self.case["caseId"], "className": "old.OtherClass"}
        with self.assertRaisesRegex(REPORT.ReportError, "actual identity"):
            REPORT.build_details(run, {"caseMappings": [item], "rules": [rule()]}, {})

    def test_minimal_planned_case_gets_consistent_profile_encoding(self):
        run = REPORT.read_run(self.path)
        item = {"caseId": "future.Case#missing", "language": "python", "protocol": "grpc",
                "profile": "grpc-protobuf", "role": "provider", "type": "int64", "vector": "minimum"}
        details = REPORT.build_details(run, {"plannedCases": [item], "rules": [rule()]}, {})
        profiles = REPORT.profiles_for(details, {}, "python", "provider")
        self.assertEqual([("grpc", "grpc-protobuf", "Protobuf")], profiles)

    def test_missing_java_time_case_defaults_to_time_section(self):
        run = REPORT.read_run(self.path)
        item = {"caseId": "future.JavaTimeTypeMatrixTest#missing", "language": "java", "protocol": "rsf",
                "profile": "rsf-java", "role": "self", "type": "instant", "vector": "before-epoch"}
        details = REPORT.build_details(run, {"plannedCases": [item], "rules": []}, {})
        missing = next(entry for entry in details if entry["testStatus"] == "missing")
        self.assertEqual("java-time", missing["section"])

    def test_unexecuted_axes_profile_is_retained_in_java_time_table(self):
        report = {"run": {"runId": "fixture", "configuration": {}, "directory": "fixture"},
                  "cases": [], "hasPlannedCases": True, "typeCaseCounts": {}, "review": {},
                  "axes": {"languages": ["java"], "protocols": ["rsf"],
                           "profiles": [{"language": "java", "role": "self", "protocol": "rsf",
                                         "profile": "rsf-java", "encoding": "Java"}]}}
        rendered = REPORT.render_markdown(report, Path("a.json"))
        time_table = rendered.split("#### Java 8 时间", 1)[1]
        self.assertIn("rsf-java / Java", time_table)
        self.assertIn("未验证", time_table)

    def test_generate_writes_markdown_and_complete_case_details(self):
        expectations = self.path / "expectations.json"
        expectations.write_text(json.dumps({"schemaVersion": 1, "review": {"reviewedBy": "fixture", "basis": "test"},
                                            "rules": [rule()],
                                            "axes": {"languages": ["python"], "protocols": ["grpc"]}}))
        output = self.path / "generated/support.md"
        result = REPORT.generate(self.path, expectations, None, output)
        self.assertEqual("supported", result["cases"][0]["support"])
        self.assertIn("Meteor 作为消费者", output.read_text())
        self.assertTrue(output.with_suffix(".json").is_file())
        self.assertFalse(output.with_suffix(".csv").exists())


class TypeMatrixMarkdownTest(unittest.TestCase):
    def entry(self, **values):
        return {
            "language": "python", "protocol": "grpc", "profile": "grpc-protobuf",
            "encoding": "Protobuf", "role": "provider", "section": "basic",
            "type": "int64", "support": "supported", "testStatus": "passed",
            "ruleId": "reviewed-fixture", "reason": "Reviewed fixture.", **values,
        }

    def render(self, cases, languages=None):
        report = {
            "run": {"runId": "fixture", "configuration": {}, "directory": "fixture"},
            "cases": cases, "hasPlannedCases": True,
            "typeCaseCounts": {"passed": len(cases)}, "review": {},
            "axes": {"languages": languages or ["python"], "protocols": ["grpc", "soap"]},
        }
        return REPORT.render_markdown(report, Path("audit.json"))

    def test_unused_type_columns_are_not_borrowed_from_another_language(self):
        rendered = self.render([self.entry()])
        table = next(line for line in rendered.splitlines() if line.startswith("| 协议 / 编码"))
        self.assertEqual("| 协议 / 编码 | int64 |", table)

    def test_protocol_without_vectors_is_summarized_without_matrix_rows(self):
        rendered = self.render([self.entry()])
        self.assertIn("未验证协议：soap。", rendered)
        self.assertNotIn("未配置 SDK/profile", rendered)

    def test_missing_direction_has_one_sentence_instead_of_empty_matrices(self):
        rendered = self.render([self.entry()])
        consumer = rendered.split("### Meteor 作为消费者", 1)[1].split("## 映射与限制", 1)[0]
        self.assertIn("未验证：本轮没有该调用方向的类型用例。", consumer)
        self.assertNotIn("| 协议", consumer)

    def test_rust_is_explicitly_unverified(self):
        rendered = self.render([self.entry()], ["python", "rust"])
        self.assertIn("## Rust\n\n未验证", rendered)

    def test_cell_reports_support_without_per_vector_pass_counts(self):
        rendered = self.render([self.entry()])
        self.assertIn("| grpc-protobuf / Protobuf | 支持 |", rendered)
        self.assertNotIn("(1/1)", rendered)

    def test_rejection_is_not_presented_as_lossless_support(self):
        rendered = self.render([self.entry(support="unsupported", outcome="rejection")])
        self.assertIn("| grpc-protobuf / Protobuf | 不支持 |", rendered)

    def test_limitation_is_shared_between_directions(self):
        entries = [self.entry(support="limited", reason="One numeric boundary."),
                   self.entry(role="consumer", support="limited", reason="One numeric boundary.")]
        rendered = self.render(entries)
        self.assertEqual(1, rendered.count("One numeric boundary."))

    def test_distinct_jayson_lexemes_share_one_readable_limitation(self):
        entries = [self.entry(language="node", protocol="jsonrpc", profile="jsonrpc", support="limited",
                              reason='固定 Jayson 数字词法 {"int64Value": "9007199254740992"} 在范围内'),
                   self.entry(language="node", protocol="jsonrpc", profile="jsonrpc", support="limited",
                              reason='固定 Jayson 数字词法 {"int64Value": "-9223372036854776000"} 超出范围')]
        rendered = self.render(entries, ["node"])
        self.assertEqual(1, rendered.count("Jayson 将整数解析为 Number"))
        self.assertNotIn("9007199254740992", rendered)

    def test_scope_uses_readable_fields_without_configuration_json_block(self):
        rendered = self.render([self.entry()])
        self.assertIn("| 运行编号 | fixture |", rendered)
        self.assertNotIn("```json", rendered)

    def test_table_type_groups_preserve_every_declared_type(self):
        names = {"boolean", "int8", "int64", "char", "text", "bytes", "optional", "future-type"}
        groups = REPORT.type_groups([self.entry(type=name) for name in names], "python")
        self.assertEqual(names, {name for _, group in groups for name in group})


if __name__ == "__main__":
    unittest.main()
