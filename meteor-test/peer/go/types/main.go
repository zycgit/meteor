// Official Go SDK type peer; it has no Meteor libraries or Java dependencies.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"math"
	"os"
	"reflect"
	"strconv"
	"strings"
)

type options struct {
	protocol, role, target, vector, readyFile, resultFile, valuesFile string
}

type typeRecord struct {
	Index int32
	Name  string
}

// Native widths are deliberate: fixture decimal texts never pass through float64.
type nativeValue struct {
	CaseId          string
	BooleanValue    bool
	Int8Value       int8
	Int16Value      int16
	Int32Value      int32
	Int64Value      int64
	Float32Value    float32
	Float64Value    float64
	TextValue       string
	BinaryValue     []byte
	EnumValue       int32
	ListValue       []string
	MapValue        map[string]int64
	RecordValue     *typeRecord
	OptionalValue   *string
	Receipt         string
	Unsigned64Value uint64
}

type vectorTable struct {
	Defaults map[string]json.RawMessage `json:"defaults"`
	Vectors  map[string]struct {
		Type  string          `json:"type"`
		Field string          `json:"field"`
		Value json.RawMessage `json:"value"`
	} `json:"vectors"`
}

var valuesFile string

func table() (*vectorTable, error) {
	data, err := os.ReadFile(valuesFile)
	if err != nil {
		return nil, err
	}
	var result vectorTable
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func decimal(raw json.RawMessage) string {
	var text string
	if json.Unmarshal(raw, &text) == nil {
		return text
	}
	return string(raw)
}

func loadVector(name string) (*nativeValue, error) {
	fixtures, err := table()
	if err != nil {
		return nil, err
	}
	vector, found := fixtures.Vectors[name]
	if !found {
		return nil, fmt.Errorf("unknown type vector %q", name)
	}
	fields := fixtures.Defaults
	fields[vector.Field] = vector.Value
	fields["caseId"], _ = json.Marshal(name)
	fields["receipt"], _ = json.Marshal("client:" + name)

	// Restore native integer / float / byte / optional types before invoking an SDK.
	result := &nativeValue{}
	plain := map[string]any{
		"caseId": &result.CaseId, "booleanValue": &result.BooleanValue,
		"int8Value": &result.Int8Value, "int16Value": &result.Int16Value,
		"int32Value": &result.Int32Value, "textValue": &result.TextValue,
		"binaryValue": &result.BinaryValue, "listValue": &result.ListValue,
		"recordValue": &result.RecordValue, "optionalValue": &result.OptionalValue,
		"receipt": &result.Receipt,
	}
	for name, pointer := range plain {
		if err := json.Unmarshal(fields[name], pointer); err != nil {
			return nil, fmt.Errorf("%s: %w", name, err)
		}
	}
	result.Int64Value, err = strconv.ParseInt(decimal(fields["int64Value"]), 10, 64)
	if err != nil {
		return nil, err
	}
	result.Unsigned64Value, err = nativeUnsigned(vector.Type, decimal(fields["unsigned64Value"]))
	if err != nil {
		return nil, err
	}
	single, err := strconv.ParseFloat(decimal(fields["float32Value"]), 32)
	if err != nil {
		return nil, err
	}
	result.Float32Value = float32(single)
	result.Float64Value, err = strconv.ParseFloat(decimal(fields["float64Value"]), 64)
	if err != nil {
		return nil, err
	}
	var kind string
	if err = json.Unmarshal(fields["enumValue"], &kind); err != nil {
		return nil, err
	}
	switch kind {
	case "FIRST":
		result.EnumValue = 0
	case "SECOND":
		result.EnumValue = 1
	default:
		return nil, errors.New("unknown enum")
	}
	var entries map[string]json.RawMessage
	if err = json.Unmarshal(fields["mapValue"], &entries); err != nil {
		return nil, err
	}
	result.MapValue = make(map[string]int64, len(entries))
	for key, raw := range entries {
		value, err := strconv.ParseInt(decimal(raw), 10, 64)
		if err != nil {
			return nil, err
		}
		result.MapValue[key] = value
	}
	return result, nil
}

// Each narrow unsigned vector is a real Go uint8/uint16/uint32 before widening to
// the shared uint64 wire field. ParseUint rejects overflow before any cast.
func nativeUnsigned(kind, text string) (uint64, error) {
	width := 64
	switch kind {
	case "uint8":
		width = 8
	case "uint16":
		width = 16
	case "uint32":
		width = 32
	}
	parsed, err := strconv.ParseUint(text, 10, width)
	if err != nil {
		return 0, err
	}
	switch kind {
	case "uint8":
		native := uint8(parsed)
		return uint64(native), nil
	case "uint16":
		native := uint16(parsed)
		return uint64(native), nil
	case "uint32":
		native := uint32(parsed)
		return uint64(native), nil
	default:
		return parsed, nil
	}
}

func validateVector(value *nativeValue) error {
	if value == nil {
		return errors.New("null type value")
	}
	expected, err := loadVector(value.CaseId)
	if err != nil {
		return err
	}
	fixtures, err := table()
	if err != nil {
		return err
	}
	vector := fixtures.Vectors[value.CaseId]
	field := vector.Field
	if field == "unsigned64Value" {
		// Decode the received value back through its declared native unsigned width.
		restored, err := nativeUnsigned(vector.Type, strconv.FormatUint(value.Unsigned64Value, 10))
		if err != nil {
			return fmt.Errorf("%s native width: %w", value.CaseId, err)
		}
		if restored != value.Unsigned64Value {
			return errors.New("unsigned width changed the received value")
		}
	}
	actualField := reflect.ValueOf(value).Elem().FieldByName(fieldName(field)).Interface()
	expectedField := reflect.ValueOf(expected).Elem().FieldByName(fieldName(field)).Interface()
	equal := reflect.DeepEqual(expectedField, actualField)
	switch field {
	case "float32Value":
		equal = math.Float32bits(expected.Float32Value) == math.Float32bits(value.Float32Value)
	case "float64Value":
		equal = math.Float64bits(expected.Float64Value) == math.Float64bits(value.Float64Value)
	case "binaryValue":
		equal = bytes.Equal(expected.BinaryValue, value.BinaryValue)
	case "listValue":
		equal = len(expected.ListValue) == len(value.ListValue) && (len(value.ListValue) == 0 || equal)
	case "mapValue":
		equal = len(expected.MapValue) == len(value.MapValue) && (len(value.MapValue) == 0 || equal)
	}
	if !equal {
		return fmt.Errorf("%s (%s) changed: expected %#v, received %#v", value.CaseId, field, expectedField, actualField)
	}
	return nil
}

func fieldName(name string) string {
	return string(name[0]-'a'+'A') + name[1:]
}

func acceptVector(value *nativeValue) (*nativeValue, error) {
	if err := validateVector(value); err != nil {
		return nil, err
	}
	if value.Receipt != "client:"+value.CaseId {
		return nil, errors.New("request receipt was not decoded")
	}
	response := *value
	response.Receipt = "accepted:" + value.CaseId
	return &response, nil
}

func validateResponse(value *nativeValue) error {
	if err := validateVector(value); err != nil {
		return err
	}
	if value.Receipt != "accepted:"+value.CaseId {
		return errors.New("provider acceptance receipt is missing")
	}
	return nil
}

func writeReady(path, address string) error {
	if err := os.WriteFile(path+".part", []byte(address), 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func writeResult(path string, err error) {
	result := map[string]any{"ok": err == nil, "machineIntBits": strconv.IntSize}
	if err != nil {
		result["message"] = err.Error()
	}
	data, _ := json.Marshal(result)
	if writeErr := os.WriteFile(path+".part", data, 0600); writeErr != nil {
		fmt.Fprintln(os.Stderr, writeErr)
		return
	}
	if writeErr := os.Rename(path+".part", path); writeErr != nil {
		fmt.Fprintln(os.Stderr, writeErr)
	}
}

func main() {
	var opts options
	flag.StringVar(&opts.protocol, "protocol", "grpc", "grpc, connect, thrift")
	flag.StringVar(&opts.role, "role", "client", "client or server")
	flag.StringVar(&opts.target, "target", "", "RPC target")
	flag.StringVar(&opts.vector, "vector", "", "one vector name")
	flag.StringVar(&opts.readyFile, "ready-file", "", "server readiness file")
	flag.StringVar(&opts.resultFile, "result-file", "", "client result file")
	flag.StringVar(&opts.valuesFile, "values-file", "", "shared type fixture")
	flag.Parse()
	valuesFile = opts.valuesFile
	var err error
	if opts.role == "server" {
		switch {
		case opts.protocol == "grpc" || opts.protocol == "connect":
			err = serveProto(opts)
		case strings.HasPrefix(opts.protocol, "thrift"):
			err = serveThrift(opts)
		default:
			err = errors.New("unsupported protocol")
		}
	} else if opts.role == "client" {
		switch {
		case opts.protocol == "grpc" || opts.protocol == "connect":
			err = callProto(opts)
		case strings.HasPrefix(opts.protocol, "thrift"):
			err = callThrift(opts)
		default:
			err = errors.New("unsupported protocol")
		}
		writeResult(opts.resultFile, err)
	} else {
		err = errors.New("unsupported role")
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
