package net.hasor.meteor.test.nxn;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** 从单次运行的原始证据生成跨语言类型支持矩阵。 */
public final class NxnTypeReport {
    private static final ObjectMapper        JSON             = new ObjectMapper();
    private static final Map<String, Object> SUPPORT_LABELS   = values( // 支持程度的显示顺序固定。
            "supported", "支持", "mapped", "映射支持", "limited", "有限支持", //
            "unsupported", "不支持", "unverified", "未验证", "failed", "失败");
    private static final List<String>        PROTOCOLS        = names( // 协议目录的显示顺序固定。
            "rsf grpc grpc-web hprose soap jsonrpc xmlrpc hessian connect triple dubbo thrift oncrpc");
    private static final Map<String, Object> LANGUAGE_LABELS  = values( // 语言目录的显示顺序固定。
            "java", "Java", "python", "Python", "node", "JavaScript / Node.js", //
            "browser", "JavaScript / 浏览器", "go", "Go", "c", "C", "rust", "Rust");
    private static final Map<String, Object> TIME_LABELS      = values( // Java 时间列的显示顺序固定。
            "local-date", "LocalDate", "local-time", "LocalTime", "local-date-time", "LocalDateTime", //
            "instant", "Instant", "offset-time", "OffsetTime", "offset-date-time", "OffsetDateTime", //
            "zoned-date-time", "ZonedDateTime", "duration", "Duration", "period", "Period", "year", "Year", //
            "year-month", "YearMonth", "month-day", "MonthDay", "zone-id", "ZoneId", "zone-offset", "ZoneOffset");
    private static final Map<String, Object> TYPE_LABELS      = values( // Java 类型名称仅用于展示。
            "boxed-boolean", "Boolean", "boxed-byte", "Byte", "boxed-short", "Short", "boxed-int", "Integer", //
            "boxed-long", "Long", "boxed-float", "Float", "boxed-double", "Double", "boxed-char", "Character", //
            "bytes", "byte[]", "big-integer", "BigInteger", "big-decimal", "BigDecimal", "uuid", "UUID", //
            "date", "Date", "int-array", "int[]", "string-array", "String[]", "list-string", "List<String>", //
            "set-string", "Set<String>", "map-string-int", "Map<String,Integer>", "dto", "DTO");
    private static final Set<String>         TYPE_CLASSES     = new LinkedHashSet<>(names("JavaBasicTypeMatrixTest JavaTimeTypeMatrixTest GoProtoTypeMatrixTest ThriftGoTypeMatrixTest " + "NodeConnectTypeScenarioTest BrowserGrpcWebTypeScenarioTest " + "NodeHproseTypeTest NodeJsonRpcTypeTest " + "PythonJsonRpcTypeTest SoapPythonTypeMatrixTest XmlRpcPythonTypeMatrixTest " + "XmlRpcPythonTypeRejectionTest PythonGrpcTypeInteropTest PythonThriftTypeInteropTest " + "OncRpcNativeTypeMatrixTest OncRpcRejectedTypeMatrixTest"));
    private static final Set<String>         SELECT_FIELDS    = new LinkedHashSet<>(names("caseId className method language protocol profile role type vector discovery encoding transport section"));
    private static final List<String>        DIMENSION_FIELDS = names("language protocol profile role type vector");
    private static final Map<String, String> METHOD_ROLES     = methodRoles();

    private NxnTypeReport() {
    }

    /** 执行 types 子命令，输入错误返回 2。 */
    public static int run(String[] arguments) {
        return run(arguments, System.out, System.err);
    }

    static int run(String[] arguments, PrintStream output, PrintStream errors) {
        try {
            // 只接收显式的单轮运行，不读取 latest 或复用其它轮次结果。
            Map<String, String> options = new LinkedHashMap<>();
            Set<String> allowed = new LinkedHashSet<>(names("--run-directory --expectations --output --details-prefix --c-catalog"));
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if (StringUtils.equals(option, "--help") || StringUtils.equals(option, "-h")) {
                    output.println("types --run-directory DIR --expectations FILE --output FILE " + "[--details-prefix PATH] [--c-catalog FILE]");
                    return 0;
                }

                require(allowed.contains(option), "Unknown types option: " + option);
                require(index + 1 < arguments.length, "Missing value for " + option);
                require(!options.containsKey(option), "Repeated types option: " + option);
                options.put(option, arguments[++index]);
            }

            for (String required : names("--run-directory --expectations --output")) {
                require(options.containsKey(required), "Required types option: " + required);
            }
            Path catalog = options.containsKey("--c-catalog") ? Path.of(options.get("--c-catalog")) : defaultCatalog();
            Path prefix = options.containsKey("--details-prefix") ? Path.of(options.get("--details-prefix")) : null;
            Map<String, Object> report = generate(Path.of(options.get("--run-directory")), //
                    Path.of(options.get("--expectations")), catalog, Path.of(options.get("--output")), prefix);

            // 标准输出只提供本轮结果摘要，详细审计信息写入 JSON。
            Map<String, Object> summary = values("typeCases", objects(report.get("cases")).size(), //
                    "testStatus", report.get("typeCaseCounts"), //
                    "support", report.get("supportCounts"));
            output.println(JSON.writeValueAsString(summary));
            return 0;
        } catch (Exception error) {
            errors.println("Type report: " + error.getMessage());
            return 2;
        }
    }

    static Map<String, Object> readRun(Path directory) throws Exception {
        directory = directory.toAbsolutePath().normalize();
        Map<String, Path> paths = new LinkedHashMap<>();
        for (String name : names("selected-cases.json summary.json configuration.json")) {
            Path path = directory.resolve(name);
            require(Files.isRegularFile(path), "Required same-run input is missing: " + path);
            paths.put(name, path);
        }

        Object selectedInput = load(paths.get("selected-cases.json"));
        require(selectedInput instanceof List<?>, "selected-cases.json must be a list");
        List<Map<String, Object>> selected = objects(selectedInput);
        Map<String, Object> summary = object(load(paths.get("summary.json")));
        Map<String, Object> configuration = object(load(paths.get("configuration.json")));
        String runId = string(summary, "runId");
        require(!runId.isEmpty() && StringUtils.equals(runId, string(configuration, "runId")), //
                "summary/configuration runId mismatch");
        boolean started = Boolean.TRUE.equals(summary.get("testTaskStarted"));
        boolean available = Boolean.TRUE.equals(summary.get("resultsAvailable"));
        require(started && available, "JUnit did not start or produced no results in this run");
        require(Boolean.FALSE.equals(summary.get("reusedResults")), //
                "Cached/reused results are not fresh type verification");
        Map<String, Map<String, Object>> actual = caseMap(selected, "selected-cases.json");
        Map<String, Integer> counts = counts(selected, "status");
        require(Set.of("passed", "failed", "skipped").containsAll(counts.keySet()), "Unknown selected testcase status");
        require(number(summary.get("tests")) == selected.size(), "summary counts differ from selected cases");
        for (String status : names("passed failed skipped")) {
            require(number(summary.get(status)) == counts.getOrDefault(status, 0), //
                    "summary counts differ from selected cases");
        }

        // XML 必须来自同一归档目录，禁止外部实体或 DTD 读取其它文件。
        List<Path> xmlFiles;
        Path resultDirectory = directory.resolve("test-results");
        if (Files.isDirectory(resultDirectory)) {
            try (var files = Files.list(resultDirectory)) {
                xmlFiles = files.filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")) //
                        .sorted().toList();
            }
        } else {
            xmlFiles = List.of();
        }

        require(!xmlFiles.isEmpty(), "No same-run test-results/TEST-*.xml files");
        List<Map<String, Object>> xmlCases = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (Path xmlFile : xmlFiles) {
            NodeList testcases = factory.newDocumentBuilder().parse(xmlFile.toFile()).getElementsByTagName("testcase");
            for (int index = 0; index < testcases.getLength(); index++) {
                Element testcase = (Element) testcases.item(index);
                String className = testcase.getAttribute("classname");
                String name = testcase.getAttribute("name");
                require(!className.isEmpty() && !name.isEmpty(), "JUnit case has no identity: " + xmlFile);
                String status = "passed";
                if (hasChild(testcase, "failure") || hasChild(testcase, "error")) {
                    status = "failed";
                } else if (hasChild(testcase, "skipped")) {
                    status = "skipped";
                }
                String caseId = className + "#" + name;
                xmlCases.add(values("caseId", caseId, "status", status, "junitXml", xmlFile.toString()));
            }
        }

        Map<String, Map<String, Object>> archived = caseMap(xmlCases, "same-run JUnit XML");
        require(actual.keySet().equals(archived.keySet()), "XML/selected identity mismatch");
        for (Map.Entry<String, Map<String, Object>> item : actual.entrySet()) {
            Map<String, Object> entry = item.getValue();
            String identity = string(entry, "className") + "#" + string(entry, "scenario");
            require(StringUtils.equals(item.getKey(), identity), "Selected identity fields disagree: " + item.getKey());
            require(StringUtils.equals(string(entry, "status"), string(archived.get(item.getKey()), "status")), //
                    "XML/selected status mismatch: " + item.getKey());
            entry.put("junitXml", archived.get(item.getKey()).get("junitXml"));
        }

        // 原始输入的摘要与每案 XML 定位一同写入审计文件。
        Map<String, Object> hashes = new LinkedHashMap<>();
        List<Path> inputs = new ArrayList<>(paths.values());
        inputs.addAll(xmlFiles);
        for (Path path : inputs) {
            hashes.put(path.toString(), sha256(path));
        }
        return values("directory", directory.toString(), "runId", runId, "summary", summary, //
                "configuration", configuration, "cases", actual, "inputSha256", hashes);
    }

    static String protocolOf(String profile) {
        if (profile.startsWith("grpcweb") || profile.startsWith("grpc-web")) {
            return "grpc-web";
        }
        for (String protocol : PROTOCOLS) {
            if (StringUtils.equals(profile, protocol) || profile.startsWith(protocol + "-")) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown RPC profile: " + profile);
    }

    static List<String> profileDetails(String profile, String protocol) {
        if (StringUtils.equals(protocol, "oncrpc")) {
            return List.of("XDR", profile.contains("udp") ? "udp" : "tcp");
        }
        if (StringUtils.equals(protocol, "thrift")) {
            String encoding = "unspecified";
            for (String candidate : names("binary compact json")) {
                if (profile.contains(candidate)) {
                    encoding = candidate;
                    break;
                }
            }
            return List.of(encoding, profile.contains("http") ? "http" : "tcp");
        }
        if (profile.contains("protobuf") || profile.startsWith("grpcweb")) {
            return List.of("Protobuf", "http");
        }
        if (profile.contains("hessian")) {
            String encoding = profile.contains("hessian2") || profile.contains("hessian-2") ? "Hessian2" : "Hessian";
            return List.of(encoding, StringUtils.equals(protocol, "dubbo") ? "tcp" : "http");
        }
        if (Set.of("hprose", "soap", "xmlrpc").contains(protocol)) {
            return List.of(StringUtils.equals(protocol, "hprose") ? "Hprose" : "XML", "http");
        }
        if (StringUtils.equals(profile, "rsf-java")) {
            return List.of("Java", "tcp");
        }
        String encoding = profile.contains("json") || StringUtils.equals(protocol, "jsonrpc") ? "JSON" : "unspecified";
        return List.of(encoding, Set.of("rsf", "dubbo").contains(protocol) ? "tcp" : "http");
    }

    static Map<String, Object> parseDimensions(Map<String, Object> testcase, Map<String, Map<String, Object>> catalog) {
        // 标准业务矩阵没有类型维度，不能再从类名或展示名推断类型支持。
        if (testcase.containsKey("matrix")) {
            return null;
        }

        String scenario = string(testcase, "scenario");
        int bracket = scenario.indexOf('[');
        String method = bracket < 0 ? scenario : scenario.substring(0, bracket);
        String className = string(testcase, "className");
        className = className.substring(className.lastIndexOf('.') + 1);
        if (bracket < 0 || !scenario.endsWith("]")) {
            require(!TYPE_CLASSES.contains(className), //
                    "Unexpanded or initialization type case: " + string(testcase, "caseId"));
            return null;
        }

        String parameter = scenario.substring(bracket + 1, scenario.length() - 1);
        String language;
        String profile;
        String discovery;
        String type;
        String vector;
        String role;
        String section = "basic";
        Map<String, Object> extra = new LinkedHashMap<>();

        // JUnit 的展开名称决定实际维度；支持语义只由审查后的 expectation 决定。
        if (parameter.startsWith("java-basic/") || parameter.startsWith("java-time/")) {
            Matcher matcher = Pattern.compile("java-(basic|time)/self/([^/]+)/([^/]+)/type=([^/]+)/vector=(.+)") //
                    .matcher(parameter);
            require(matcher.matches(), "Malformed Java type parameters: " + parameter);
            language = "java";
            role = "self";
            section = StringUtils.equals(matcher.group(1), "time") ? "java-time" : "basic";
            profile = matcher.group(2);
            discovery = matcher.group(3);
            type = matcher.group(4);
            vector = matcher.group(5);
        } else if (parameter.startsWith("types/")) {
            String[] tokens = parameter.split("/", -1);
            require(tokens.length >= 6 && tokens.length <= 8, "Malformed foreign type parameters: " + parameter);
            language = tokens[1].toLowerCase(Locale.ROOT);
            require(LANGUAGE_LABELS.containsKey(language), "Unknown language in type parameters: " + language);
            profile = tokens[2];
            discovery = tokens[3];
            type = tokens[4];
            vector = tokens[5];
            role = METHOD_ROLES.get(method);
            for (int index = 6; index < tokens.length; index++) {
                String token = tokens[index];
                if (token.startsWith("meteor-")) {
                    String declared = token.substring("meteor-".length());
                    require(role == null || StringUtils.equals(role, declared), //
                            "Method/display role mismatch: " + string(testcase, "caseId"));
                    role = declared;
                } else {
                    require(StringUtils.equals(token, "rejection"), "Unknown type parameter suffix: " + token);
                }
            }
            require(role != null, "No explicit Meteor role mapping: " + string(testcase, "caseId"));
            if (StringUtils.equals(className, "GoProtoTypeMatrixTest") && Set.of("grpc", "connect").contains(profile)) {
                profile += "-protobuf";
            }
        } else if (Set.of("OncRpcNativeTypeMatrixTest", "OncRpcRejectedTypeMatrixTest").contains(className)) {
            Matcher matcher = Pattern.compile("(tcp|udp)/(.+)").matcher(parameter);
            require(matcher.matches(), "Malformed C parameters: " + parameter);
            vector = matcher.group(2);
            require(catalog.containsKey(vector), "C vector missing from reviewed type catalog: " + vector);
            Map<String, Object> nativeType = catalog.get(vector);
            type = string(nativeType, "type");
            extra.put("nativeType", nativeType.get("nativeType"));
            extra.put("wireType", nativeType.get("wireType"));
            language = "c";
            profile = "oncrpc-xdr-" + matcher.group(1);
            discovery = "direct";
            role = METHOD_ROLES.get(method);
            require(role != null, "No explicit C role mapping: " + string(testcase, "caseId"));
        } else {
            require(!TYPE_CLASSES.contains(className), //
                    "Unknown expanded type case format: " + string(testcase, "caseId"));
            return null;
        }
        profile = profile.toLowerCase(Locale.ROOT);
        require(Set.of("self", "provider", "consumer").contains(role), //
                "Unknown Meteor role: " + string(testcase, "caseId"));
        boolean rejection = List.of(parameter.split("/", -1)).contains("rejection");
        rejection |= className.contains("RejectedType") || className.contains("RejectionTest");
        rejection |= StringUtils.equals(vector, "string-unicode-rejected");
        if (rejection) {
            extra.put("declaredObservation", "rejection");
        }
        String protocol = protocolOf(profile);
        List<String> details = profileDetails(profile, protocol);
        Map<String, Object> result = values("language", language, "protocol", protocol, "profile", profile, //
                "encoding", details.get(0), "transport", details.get(1), "role", role, "discovery", discovery, //
                "section", section, "type", type, "vector", vector, "method", method);
        result.putAll(extra);
        return result;
    }

    static Map<String, Object> readExpectations(Path path) throws IOException {
        Map<String, Object> data = object(load(path));
        Map<String, Object> review = object(data.get("review"));
        boolean reviewed = !string(review, "reviewedBy").isEmpty() && !string(review, "basis").isEmpty();
        boolean supportedSchema = number(data.get("schemaVersion")) == 1;
        require(supportedSchema && reviewed, "Expectations require schemaVersion=1 and review.reviewedBy/basis");
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> rule : objects(data.get("rules"))) {
            String id = string(rule, "id");
            require(!id.isEmpty() && seen.add(id), "Expectation rules require unique IDs");
            Map<String, Object> selection = object(rule.get("select"));
            require(!selection.isEmpty() && SELECT_FIELDS.containsAll(selection.keySet()), //
                    "Unsupported selector in rule: " + id);
            String support = string(rule, "support");
            require(SUPPORT_LABELS.containsKey(support) && !StringUtils.equals(support, "failed"), //
                    "Unknown expectation support: " + id);
            String outcome = string(rule, "outcome", "roundtrip");
            require(Set.of("roundtrip", "rejection", "normalization").contains(outcome), //
                    "Unknown expectation outcome: " + id);
            require(!StringUtils.equals(support, "unsupported") || StringUtils.equals(outcome, "rejection"), //
                    "Unsupported claims require a precise rejection expectation: " + id);
            require(!string(rule, "reason").isEmpty(), "Expectation reason is required: " + id);
            Object priority = rule.getOrDefault("priority", 0);
            require(priority instanceof Integer || priority instanceof Long, "Rule priority must be an integer: " + id);
        }
        caseMap(objects(data.get("plannedCases")), "plannedCases");
        caseMap(objects(data.get("caseMappings")), "caseMappings");
        return data;
    }

    static boolean matches(Map<String, Object> rule, Map<String, Object> testcase) {
        for (Map.Entry<String, Object> selection : object(rule.get("select")).entrySet()) {
            List<?> patterns = selection.getValue() instanceof List<?> list ? list : List.of(selection.getValue());
            boolean matched = false;
            for (Object pattern : patterns) {
                require(pattern instanceof String, "Selector patterns must be strings: " + string(rule, "id"));
                boolean caseIdentity = StringUtils.equals(selection.getKey(), "caseId");
                String expression = glob((String) pattern, caseIdentity);
                matched |= Pattern.matches(expression, string(testcase, selection.getKey()));
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> applyExpectation(Map<String, Object> testcase, List<Map<String, Object>> rules) {
        Map<String, Object> best = null;
        long priority = Long.MIN_VALUE;
        int winners = 0;
        for (Map<String, Object> rule : rules) {
            if (!matches(rule, testcase)) {
                continue;
            }
            long candidate = number(rule.getOrDefault("priority", 0));
            if (candidate > priority) {
                best = rule;
                priority = candidate;
                winners = 1;
            } else if (candidate == priority) {
                winners++;
            }
        }
        require(winners < 2, "Ambiguous expectation rules for " + string(testcase, "caseId"));
        if (best != null) {
            String outcome = string(best, "outcome", "roundtrip");
            boolean rejection = StringUtils.equals(string(testcase, "declaredObservation"), "rejection");
            require(!rejection || StringUtils.equals(outcome, "rejection"), //
                    "Rejection testcase cannot use a round-trip expectation: " + string(testcase, "caseId"));
            boolean lossy = Set.of("rejection", "normalization").contains(outcome);
            boolean losslessSupport = Set.of("supported", "mapped").contains(string(best, "support"));
            require(!lossy || !losslessSupport, //
                    "A rejection/normalization cannot establish lossless support: " + string(testcase, "caseId"));
        }
        testcase.put("expectedSupport", best == null ? "unverified" : best.get("support"));
        testcase.put("outcome", best == null ? "unreviewed" : best.getOrDefault("outcome", "roundtrip"));
        testcase.put("ruleId", best == null ? "" : best.get("id"));
        testcase.put("reason", best == null ? "该精确场景没有经审查的支持语义。" : best.get("reason"));
        for (String field : names("nativeType wireType sdk encoding transport")) {
            if (best != null && best.containsKey(field)) {
                testcase.put(field, best.get(field));
            }
        }
        String support = "unverified";
        if (StringUtils.equals(string(testcase, "testStatus"), "failed")) {
            support = "failed";
        } else if (StringUtils.equals(string(testcase, "testStatus"), "passed")) {
            support = string(testcase, "expectedSupport");
        }
        testcase.put("support", support);
        return testcase;
    }

    static List<Map<String, Object>> buildDetails(Map<String, Object> run, Map<String, Object> expectations, Map<String, Map<String, Object>> catalog) {
        Map<String, Map<String, Object>> overrides = caseMap(objects(expectations.get("caseMappings")), "caseMappings");
        Map<String, Map<String, Object>> planned = caseMap(objects(expectations.get("plannedCases")), "plannedCases");
        Set<String> fields = new LinkedHashSet<>(SELECT_FIELDS);
        fields.addAll(names("nativeType wireType sdk"));
        List<Map<String, Object>> declarations = new ArrayList<>(overrides.values());
        declarations.addAll(planned.values());
        for (Map<String, Object> declaration : declarations) {
            require(fields.containsAll(declaration.keySet()), "Planned case contains non-dimension/execution fields");
        }
        Map<String, Map<String, Object>> actualCases = caseMap(objects(object(run.get("cases")).values()), "run cases");
        Map<String, Map<String, Object>> result = new TreeMap<>();
        List<Map<String, Object>> rules = objects(expectations.get("rules"));
        for (Map.Entry<String, Map<String, Object>> item : actualCases.entrySet()) {
            String caseId = item.getKey();
            Map<String, Object> actual = item.getValue();
            Map<String, Object> dimensions = parseDimensions(actual, catalog);
            Map<String, Object> explicit = overrides.getOrDefault(caseId, planned.get(caseId));
            String method = string(actual, "scenario").split("\\[", 2)[0];
            if (explicit != null && !explicit.isEmpty()) {
                Map<String, Object> identities = values("className", actual.get("className"), "method", method);
                checkMapping(explicit, identities, identities.keySet(), "actual identity", caseId);
                if (dimensions != null) {
                    checkMapping(explicit, dimensions, DIMENSION_FIELDS, "display", caseId);
                } else {
                    dimensions = new LinkedHashMap<>();
                }
                dimensions.putAll(explicit);
            }
            if (dimensions == null) {
                continue;
            }
            requireDimensions(dimensions, caseId);
            Map<String, Object> entry = values("runId", run.get("runId"), "caseId", caseId, //
                    "className", actual.get("className"), "method", method, "testStatus", actual.get("status"), //
                    "seconds", actual.getOrDefault("seconds", ""), "failure", actual.getOrDefault("failure", ""), //
                    "junitXml", actual.get("junitXml"));
            entry.putAll(dimensions);
            result.put(caseId, applyExpectation(completeDimensions(entry), rules));
        }

        // 已计划但未执行的向量保持 missing，邻近向量通过不能替代它。
        for (Map.Entry<String, Map<String, Object>> item : planned.entrySet()) {
            String caseId = item.getKey();
            if (result.containsKey(caseId)) {
                continue;
            }
            require(!actualCases.containsKey(caseId), "Planned actual case was not classified: " + caseId);
            Map<String, Object> entry = new LinkedHashMap<>(item.getValue());
            String[] identity = caseId.split("#", 2);
            entry.put("runId", run.get("runId"));
            entry.put("className", identity[0]);
            entry.put("method", identity.length < 2 ? "" : identity[1].split("\\[", 2)[0]);
            entry.put("testStatus", "missing");
            entry.put("junitXml", "");
            requireDimensions(entry, caseId);
            result.put(caseId, applyExpectation(completeDimensions(entry), rules));
        }
        return new ArrayList<>(result.values());
    }

    static Map<String, Object> aggregate(List<Map<String, Object>> entries) {
        Set<String> statuses = new LinkedHashSet<>();
        for (Map<String, Object> entry : entries) {
            statuses.add(string(entry, "support"));
        }
        String support;
        if (statuses.contains("failed")) {
            support = "failed";
        } else if (statuses.isEmpty() || statuses.contains("unverified")) {
            support = "unverified";
        } else if (statuses.equals(Set.of("unsupported"))) {
            support = "unsupported";
        } else if (statuses.contains("limited") || statuses.contains("unsupported")) {
            support = "limited";
        } else if (statuses.contains("mapped")) {
            support = "mapped";
        } else {
            support = "supported";
        }
        Map<String, Integer> counts = counts(entries, "testStatus");
        return values("support", support, "passed", counts.getOrDefault("passed", 0), "total", entries.size(), //
                "missing", counts.getOrDefault("missing", 0), "skipped", counts.getOrDefault("skipped", 0));
    }

    static List<List<String>> profilesFor(List<Map<String, Object>> entries, Map<String, Object> axes, String language, String role) {
        Set<List<String>> profiles = new TreeSet<>(NxnTypeReport::compareNames);
        for (Map<String, Object> entry : entries) {
            profiles.add(profileKey(entry));
        }
        for (Map<String, Object> planned : objects(axes.get("profiles"))) {
            boolean selectedLanguage = !planned.containsKey("language") || planned.get("language") == null //
                    || StringUtils.equals(string(planned, "language"), language);
            boolean selectedRole = !planned.containsKey("role") || planned.get("role") == null //
                    || StringUtils.equals(string(planned, "role"), role);
            if (selectedLanguage && selectedRole) {
                List<String> details = profileDetails(string(planned, "profile"), string(planned, "protocol"));
                profiles.add(List.of(string(planned, "protocol"), string(planned, "profile"), //
                        string(planned, "encoding", details.get(0))));
            }
        }
        return new ArrayList<>(profiles);
    }

    static Map<String, List<String>> typeGroups(List<Map<String, Object>> entries, String language) {
        Set<String> remaining = new TreeSet<>();
        for (Map<String, Object> entry : entries) {
            if (!StringUtils.equals(string(entry, "section"), "java-time")) {
                remaining.add(string(entry, "type"));
            }
        }
        Map<String, String> declared = new LinkedHashMap<>();
        if (StringUtils.equals(language, "java")) {
            declared.put("基础类型", "boolean byte short int long float double char");
            String boxedTypes = "boxed-boolean boxed-byte boxed-short boxed-int " + "boxed-long boxed-float boxed-double boxed-char";
            declared.put("包装类型", boxedTypes);
            declared.put("文本、二进制及常见值", "string bytes big-integer big-decimal uuid date");
            declared.put("集合与结构", "int-array string-array list-string set-string map-string-int dto");
        } else {
            declared.put("布尔与有符号整数", "boolean int8 int16 int32 int64 signed-char short long");
            String unsignedTypes = "uint8 uint16 uint32 uint64 float32 float64 " + "unsigned-char unsigned-short unsigned-long";
            declared.put("无符号整数与浮点数", unsignedTypes);
            declared.put("文本、二进制与空值", "text string binary fixed-binary enum optional");
            declared.put("容器与结构", "list map record array fixed-array union optional-struct");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> group : declared.entrySet()) {
            List<String> present = new ArrayList<>();
            for (String type : names(group.getValue())) {
                if (remaining.remove(type)) {
                    present.add(type);
                }
            }
            splitGroup(result, group.getKey(), present);
        }
        splitGroup(result, "其它声明类型", new ArrayList<>(remaining));
        return result;
    }

    static String matrixTable(List<Map<String, Object>> entries, List<List<String>> profiles, List<String> types) {
        Map<List<String>, List<Map<String, Object>>> cells = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            List<String> key = new ArrayList<>(profileKey(entry));
            key.add(string(entry, "type"));
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        List<String> headers = new ArrayList<>();
        headers.add("协议 / 编码");
        for (String type : types) {
            headers.add("`" + typeLabel(type) + "`");
        }
        List<List<String>> rows = new ArrayList<>();
        for (List<String> profile : profiles) {
            List<String> row = new ArrayList<>();
            row.add(profile.get(1) + " / " + profile.get(2));
            for (String type : types) {
                List<String> key = new ArrayList<>(profile);
                key.add(type);
                String support = string(aggregate(cells.getOrDefault(key, List.of())), "support");
                row.add(string(SUPPORT_LABELS, support));
            }
            rows.add(row);
        }
        return markdownTable(headers, rows);
    }

    static boolean matchingDirections(List<Map<String, Object>> entries, Map<String, Object> axes, String language) {
        List<Object> signatures = new ArrayList<>();
        List<String> dimensions = names("protocol profile encoding transport discovery section type");
        for (String role : names("provider consumer")) {
            List<Map<String, Object>> selected = filter(entries, "role", role);
            if (selected.isEmpty()) {
                return false;
            }
            Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            Map<List<Object>, Integer> vectors = new LinkedHashMap<>();
            for (Map<String, Object> entry : selected) {
                if (!StringUtils.equals(string(entry, "testStatus"), "passed")) {
                    return false;
                }
                List<Object> key = new ArrayList<>();
                for (String dimension : dimensions) {
                    key.add(entry.get(dimension));
                }
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
                List<Object> vector = new ArrayList<>(key);
                vector.add(entry.get("vector"));
                vectors.merge(vector, 1, Integer::sum);
            }
            Map<List<Object>, Object> supports = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> group : groups.entrySet()) {
                supports.put(group.getKey(), aggregate(group.getValue()).get("support"));
            }
            signatures.add(List.of(profilesFor(selected, axes, language, role), supports, vectors));
        }
        return signatures.get(0).equals(signatures.get(1));
    }

    static String renderMarkdown(Map<String, Object> report, Path detailsPath, Path reportDirectory) throws IOException {
        List<Map<String, Object>> cases = objects(report.get("cases"));
        Map<String, Object> run = object(report.get("run"));
        Map<String, Object> axes = object(report.get("axes"));
        List<String> languages = strings(axes.getOrDefault("languages", LANGUAGE_LABELS.keySet()));
        List<String> protocols = strings(axes.getOrDefault("protocols", PROTOCOLS));
        Map<String, Object> counts = object(report.get("typeCaseCounts"));
        Map<String, Object> configuration = object(run.get("configuration"));
        Map<String, Object> platform = object(configuration.get("executionPlatform"));
        String scope = Boolean.TRUE.equals(report.get("hasPlannedCases")) ? "显式计划的类型用例" : "本轮实际执行的类型向量";
        List<String> lines = new ArrayList<>();
        lines.add("# 跨语言类型支持矩阵\n");
        lines.add("按语言和调用方向分节，行表示协议与编码配置，列表示数据类型。支持结论限定于" + scope + "。\n");
        lines.add("支持：所列向量保真往返；映射支持：经明确的线格式转换保留值；有限支持：存在范围、精度或规范化限制；" + "不支持：已验证精确拒绝；未验证：本轮无证据；失败：验证结果与期望不符。\n");
        lines.add("类型用例 " + cases.size() + " 项：通过 " + counts.getOrDefault("passed", 0) //
                + "，失败 " + counts.getOrDefault("failed", 0) + "，跳过 " + counts.getOrDefault("skipped", 0) //
                + "，未执行 " + counts.getOrDefault("missing", 0) + "。通过数包含精确拒绝和规范化测试，不能等同于无损支持。\n");
        lines.add("[类型范围与复现说明](METEOR_TYPE_MATRIX_PLAN.md)。\n");
        lines.add("## 验证范围\n");
        String environment = string(platform, "os", "未记录") + " / " + string(platform, "architecture", "未记录") //
                + " / " + string(platform, "javaVersion", "未记录");
        String detailLink = detailLink(detailsPath, reportDirectory);
        List<List<String>> scopeRows = new ArrayList<>();
        scopeRows.add(List.of("执行环境", environment));
        scopeRows.add(List.of("测试筛选", filterDescription(configuration.get("includes"))));
        scopeRows.add(List.of("矩阵筛选", filterDescription(configuration.get("matrixSelection"))));
        scopeRows.add(List.of("审计数据", "[JSON](" + detailLink + ")，保存运行路径、SDK、逐案结果和审查依据"));
        lines.add(markdownTable(List.of("项目", "本轮证据"), scopeRows) + "\n");
        lines.add("类型矩阵覆盖本轮直连场景，不推导所有类型与 TLS、注册发现的全排列。" + "Java 自体用例检查 DTO 中的实际声明字段，不推导根值调用或任意 Object 多态映射。\n");

        // 双向矩阵只有向量、发现方式及结论全部相同时才合并展示。
        Map<String, Object> roleNames = values("self", "Java 自体", "provider", "Meteor 作为提供者", //
                "consumer", "Meteor 作为消费者");
        for (String language : languages) {
            List<Map<String, Object>> languageCases = filter(cases, "language", language);
            lines.add("## " + string(LANGUAGE_LABELS, language, language) + "\n");
            boolean plannedLanguage = false;
            for (Map<String, Object> profile : objects(axes.get("profiles"))) {
                boolean anyLanguage = profile.get("language") == null;
                boolean selectedLanguage = StringUtils.equals(string(profile, "language"), language);
                plannedLanguage |= anyLanguage || selectedLanguage;
            }
            if (languageCases.isEmpty() && !plannedLanguage) {
                lines.add("未验证：本轮没有该语言的类型用例，不借用其它语言或 SDK 的结果。\n");
                continue;
            }
            Map<String, List<String>> roleGroups = new LinkedHashMap<>();
            List<String> roles = names("provider consumer");
            if (StringUtils.equals(language, "java")) {
                roles = names("self provider consumer");
            }
            for (String role : roles) {
                roleGroups.put(string(roleNames, role), List.of(role));
            }
            if (!StringUtils.equals(language, "java") && matchingDirections(languageCases, axes, language)) {
                roleGroups.clear();
                roleGroups.put("提供者与消费者（双向验证）", names("provider consumer"));
            }
            for (Map.Entry<String, List<String>> group : roleGroups.entrySet()) {
                appendRole(lines, languageCases, axes, language, protocols, group.getKey(), group.getValue());
            }
        }

        // 逐向量词法保留在 JSON，Markdown 对同一语言/协议/原因只展示一次。
        Map<List<String>, List<Map<String, Object>>> reviewed = new TreeMap<>(NxnTypeReport::compareNames);
        for (Map<String, Object> entry : cases) {
            if (!string(entry, "ruleId").isEmpty() && !StringUtils.equals(string(entry, "support"), "supported")) {
                String reason = limitationReason(entry);
                List<String> key = List.of(string(entry, "language"), string(entry, "protocol"), reason);
                reviewed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            }
        }
        lines.add("## 映射与限制\n");
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<List<String>, List<Map<String, Object>>> group : reviewed.entrySet()) {
            List<String> labels = new ArrayList<>();
            Set<String> supports = new LinkedHashSet<>();
            Set<String> types = new TreeSet<>();
            for (Map<String, Object> entry : group.getValue()) {
                supports.add(string(entry, "support"));
                types.add(string(entry, "type"));
            }
            for (String support : SUPPORT_LABELS.keySet()) {
                if (supports.contains(support)) {
                    labels.add(string(SUPPORT_LABELS, support));
                }
            }
            List<String> typeNames = new ArrayList<>();
            for (String type : types) {
                typeNames.add("`" + typeLabel(type) + "`");
            }
            String language = group.getKey().get(0);
            rows.add(List.of(string(LANGUAGE_LABELS, language, language), group.getKey().get(1), //
                    String.join("、", labels), String.join("、", typeNames), group.getKey().get(2)));
        }
        if (rows.isEmpty()) {
            lines.add("本轮没有已审查的映射、限制或拒绝项。\n");
        } else {
            lines.add(markdownTable(List.of("语言", "协议", "支持程度", "数据类型", "映射或限制"), rows) + "\n");
        }
        return String.join("\n", lines);
    }

    static Map<String, Object> generate(Path runDirectory, Path expectationsPath, Path catalogPath, Path output, Path detailsPrefix) throws Exception {
        Map<String, Object> run = readRun(runDirectory);
        Map<String, Object> expectations = readExpectations(expectationsPath);
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        if (catalogPath != null && Files.isRegularFile(catalogPath)) {
            Map<String, Object> input = object(load(catalogPath));
            require(number(input.get("schemaVersion")) == 1, "C catalog requires schemaVersion=1");
            for (Map<String, Object> entry : objects(input.get("vectors"))) {
                String vector = string(entry, "vector");
                require(!catalog.containsKey(vector), "Duplicate C vector: " + vector);
                catalog.put(vector, entry);
            }
        }
        List<Map<String, Object>> cases = buildDetails(run, expectations, catalog);
        require(!cases.isEmpty(), "The explicit run contains no recognized or explicitly mapped type cases");
        Map<String, Object> metadata = new LinkedHashMap<>(run);
        metadata.remove("cases");
        Object review = expectations.get("review");
        Map<String, Object> report = values("schemaVersion", 1, "run", metadata, "review", review, //
                "expectationsFile", expectationsPath.toAbsolutePath().normalize().toString(), //
                "expectationsSha256", sha256(expectationsPath), //
                "cCatalogSha256", catalog.isEmpty() ? null : sha256(catalogPath), //
                "hasPlannedCases", !objects(expectations.get("plannedCases")).isEmpty(), //
                "axes", expectations.getOrDefault("axes", Map.of()), "typeCaseCounts", counts(cases, "testStatus"), //
                "supportCounts", counts(cases, "support"), "cases", cases);
        Path prefix = detailsPrefix == null ? changeExtension(output, "") : detailsPrefix;
        Path detailsPath = changeExtension(prefix, ".json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectories(detailsPath.toAbsolutePath().getParent());
        Files.writeString(detailsPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        Files.writeString(output, renderMarkdown(report, detailsPath, output.toAbsolutePath().getParent()));
        return report;
    }

    private static void appendRole(List<String> lines, List<Map<String, Object>> languageCases, Map<String, Object> axes, String language, List<String> protocols, String roleName, List<String> selectedRoles) {
        List<Map<String, Object>> roleCases = new ArrayList<>();
        for (Map<String, Object> entry : languageCases) {
            if (selectedRoles.contains(string(entry, "role"))) {
                roleCases.add(entry);
            }
        }
        Set<List<String>> profiles = new TreeSet<>(NxnTypeReport::compareNames);
        for (String role : selectedRoles) {
            profiles.addAll(profilesFor(roleCases, axes, language, role));
        }
        lines.add("### " + roleName + "\n");
        if (profiles.isEmpty()) {
            lines.add("未验证：本轮没有该调用方向的类型用例。\n");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String protocol : protocols) {
            boolean present = false;
            for (List<String> profile : profiles) {
                present |= StringUtils.equals(profile.get(0), protocol);
            }
            if (!present) {
                missing.add(protocol);
            }
        }
        if (!missing.isEmpty()) {
            lines.add("未验证协议：" + String.join("、", missing) + "。\n");
        }
        List<List<String>> selectedProfiles = new ArrayList<>(profiles);
        for (Map.Entry<String, List<String>> group : typeGroups(roleCases, language).entrySet()) {
            lines.add("**" + group.getKey() + "**\n");
            lines.add(matrixTable(roleCases, selectedProfiles, group.getValue()) + "\n");
        }
        List<Map<String, Object>> timeCases = filter(roleCases, "section", "java-time");
        if (StringUtils.equals(language, "java") && (!timeCases.isEmpty() || selectedRoles.contains("self"))) {
            lines.add("#### Java 8 时间\n");
            List<String> selectedTypes = new ArrayList<>();
            for (String type : TIME_LABELS.keySet()) {
                if (!filter(timeCases, "type", type).isEmpty()) {
                    selectedTypes.add(type);
                }
            }
            if (selectedTypes.isEmpty()) {
                selectedTypes.addAll(TIME_LABELS.keySet());
            }
            for (int offset = 0; offset < selectedTypes.size(); offset += 7) {
                List<String> types = selectedTypes.subList(offset, Math.min(offset + 7, selectedTypes.size()));
                lines.add(matrixTable(timeCases, selectedProfiles, types) + "\n");
            }
        }
    }

    private static String limitationReason(Map<String, Object> entry) {
        String reason = string(entry, "reason");
        boolean jayson = StringUtils.equals(string(entry, "language"), "node") //
                && StringUtils.equals(string(entry, "protocol"), "jsonrpc") && reason.startsWith("固定 Jayson 数字词法");
        if (jayson) {
            return "Jayson 将整数解析为 Number；超过安全整数范围会舍入。Meteor 提供者按舍入后的数字词法接收，" + "超出 Java long 范围时返回 Invalid params；消费者方向由测试业务 guard 明确拒绝不安全整数。";
        }
        return reason;
    }

    private static Map<String, Object> completeDimensions(Map<String, Object> entry) {
        List<String> details = profileDetails(string(entry, "profile"), string(entry, "protocol"));
        entry.putIfAbsent("encoding", details.get(0));
        entry.putIfAbsent("transport", details.get(1));
        entry.putIfAbsent("discovery", "direct");
        boolean javaTime = StringUtils.equals(string(entry, "language"), "java") //
                && TIME_LABELS.containsKey(string(entry, "type"));
        entry.putIfAbsent("section", javaTime ? "java-time" : "basic");
        require(!javaTime || StringUtils.equals(string(entry, "section"), "java-time"), //
                "Java time type cannot be hidden in a basic section: " + string(entry, "caseId"));
        return entry;
    }

    private static void checkMapping(Map<String, Object> explicit, Map<String, Object> actual, Collection<String> fields, String source, String caseId) {
        for (String field : fields) {
            if (explicit.containsKey(field)) {
                require(StringUtils.equals(string(explicit, field), string(actual, field)), //
                        "Explicit mapping contradicts " + source + " " + field + ": " + caseId);
            }
        }
    }

    private static void requireDimensions(Map<String, Object> entry, String caseId) {
        for (String field : DIMENSION_FIELDS) {
            require(!string(entry, field).isEmpty(), "Incomplete type dimensions " + field + ": " + caseId);
        }
    }

    private static String glob(String pattern, boolean caseIdentity) {
        StringBuilder expression = new StringBuilder("(?s)");
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '*') {
                expression.append(".*");
            } else if (character == '?') {
                expression.append('.');
            } else if (character == '[' && !caseIdentity) {
                int end = pattern.indexOf(']', index + 1);
                if (end > index + 1) {
                    String selection = pattern.substring(index + 1, end);
                    if (selection.startsWith("!")) {
                        selection = "^" + selection.substring(1);
                    } else if (selection.startsWith("^")) {
                        selection = "\\^" + selection.substring(1);
                    }
                    expression.append('[').append(selection.replace("\\", "\\\\")).append(']');
                    index = end;
                } else {
                    expression.append("\\[");
                }
            } else {
                expression.append(Pattern.quote(Character.toString(character)));
            }
        }
        return expression.toString();
    }

    private static Map<String, String> methodRoles() {
        Map<String, String> result = new LinkedHashMap<>();
        String providerMethods = "meteorProviderPreservesPythonNativeType meteorProviderAcceptsOneNodeTypeVector " + "meteorProviderAcceptsOnePythonTypeVector meteorProviderAcceptsPythonNativeType " + "pythonConsumerCallsMeteorProvider pythonConsumerRejectsIntegerOutsideWireRange " + "providerAcceptsOneNativeGoTypeVector goConsumerPreservesSelectedTypeOnMeteorProvider " + "officialBrowserPreservesDeclaredType meteorProviderAcceptsNativeType";
        String consumerMethods = "meteorConsumerPreservesPythonNativeType meteorConsumerAcceptsOneNodeTypeVector " + "meteorConsumerAcceptsOnePythonTypeVector meteorConsumerReadsPythonNativeType " + "meteorConsumerCallsPythonProvider meteorConsumerRejectsIntegerOutsideWireRange " + "consumerReceivesOneNativeGoTypeVector meteorConsumerPreservesSelectedTypeOnGoProvider " + "meteorConsumerReadsNativeType meteorConsumerRejectsUnrepresentableType";
        for (String method : names(providerMethods)) {
            result.put(method, "provider");
        }
        for (String method : names(consumerMethods)) {
            result.put(method, "consumer");
        }
        return result;
    }

    private static Path defaultCatalog() {
        Path root = Path.of(System.getProperty("meteor.test.root", "meteor-test"));
        Path catalog = root.resolve("peer/c/resource/c-type-catalog.json");
        if (!Files.isRegularFile(catalog)) {
            catalog = Path.of("peer/c/resource/c-type-catalog.json");
        }
        return catalog;
    }

    private static Path changeExtension(Path path, String suffix) {
        String name = path.getFileName().toString();
        int separator = name.lastIndexOf('.');
        if (separator > 0) {
            name = name.substring(0, separator);
        }
        return path.resolveSibling(name + suffix);
    }

    private static String detailLink(Path path, Path directory) {
        if (directory == null) {
            return path.getFileName().toString();
        }
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return directory.toAbsolutePath().normalize().relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException error) {
            return absolute.toString().replace('\\', '/');
        }
    }

    private static String filterDescription(Object value) throws IOException {
        boolean empty = value == null || value instanceof Collection<?> collection && collection.isEmpty();
        empty |= value instanceof Map<?, ?> map && map.isEmpty();
        empty |= value instanceof String text && text.isEmpty();
        return empty ? "未筛选" : JSON.writeValueAsString(value);
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> entries, String field, String value) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (StringUtils.equals(string(entry, field), value)) {
                selected.add(entry);
            }
        }
        return selected;
    }

    private static List<String> profileKey(Map<String, Object> entry) {
        return List.of(string(entry, "protocol"), string(entry, "profile"), string(entry, "encoding", "unspecified"));
    }

    private static void splitGroup(Map<String, List<String>> result, String title, List<String> types) {
        for (int offset = 0; offset < types.size(); offset += 8) {
            String suffix = offset == 0 ? "" : "（续" + (offset == 8 ? "" : offset / 8) + "）";
            result.put(title + suffix, new ArrayList<>(types.subList(offset, Math.min(offset + 8, types.size()))));
        }
    }

    private static int compareNames(List<String> first, List<String> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int comparison = first.get(index).compareTo(second.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    private static String typeLabel(String name) {
        return string(TIME_LABELS, name, string(TYPE_LABELS, name, name));
    }

    private static String markdownTable(List<String> headers, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(markdownRow(headers));
        List<String> separators = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            separators.add("---");
        }
        lines.add(markdownRow(separators));
        for (List<String> row : rows) {
            lines.add(markdownRow(row));
        }
        return String.join("\n", lines);
    }

    private static String markdownRow(List<String> values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            escaped.add(value.replace("|", "\\|").replace('\n', ' '));
        }
        return "| " + String.join(" | ", escaped) + " |";
    }

    private static boolean hasChild(Element element, String name) {
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (StringUtils.equals(nodes.item(index).getNodeName(), name)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Map<String, Object>> caseMap(List<Map<String, Object>> entries, String source) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            Object id = entry.get("caseId");
            require(id instanceof String && !((String) id).isEmpty(), "Missing caseId in " + source);
            String caseId = (String) id;
            require(!result.containsKey(caseId), "Duplicate caseId in " + source + ": " + caseId);
            result.put(caseId, entry);
        }
        return result;
    }

    private static Map<String, Integer> counts(List<Map<String, Object>> entries, String field) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            counts.merge(string(entry, field), 1, Integer::sum);
        }
        return counts;
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Object load(Path path) throws IOException {
        return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
    }

    static Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>((Collection<Map<String, Object>>) collection);
        }
        throw new IllegalArgumentException("Expected a JSON array");
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : (Collection<?>) value) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static List<String> names(String names) {
        return List.of(names.split(" "));
    }

    static String string(Map<String, ?> entry, String key) {
        return string(entry, key, "");
    }

    private static String string(Map<String, ?> entry, String key, String defaultValue) {
        Object value = entry.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return Long.MIN_VALUE;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
