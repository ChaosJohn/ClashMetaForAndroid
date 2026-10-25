package config

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func mustJson(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

func mustParse(t *testing.T, text string) ManualConfig {
	t.Helper()

	out, err := ParseManualImport(text)
	if err != nil {
		t.Fatalf("ParseManualImport: %v", err)
	}

	cfg := ManualConfig{}
	if err := json.Unmarshal([]byte(out), &cfg); err != nil {
		t.Fatalf("normalized JSON: %v", err)
	}

	return cfg
}

func TestExportParseRoundTrip(t *testing.T) {
	cfg := ManualConfig{
		Proxies: []map[string]any{
			{
				"name": "我的节点", "type": "ss", "server": "example.com",
				"port": 443, "cipher": "aes-256-gcm", "password": "secret", "udp": true,
				"extras": map[string]any{"ws-opts": `{"path":"/ws"}`},
			},
		},
		ProxyGroup: []map[string]any{
			{
				"name": "手动组", "type": "select",
				"proxies": []any{"我的节点", "香港自动", "DIRECT"},
				"options": map[string]any{"url": "https://www.gstatic.com/generate_204", "interval": "300"},
			},
		},
		Rules: []ManualRule{
			{Rule: "DOMAIN-SUFFIX,example.org", Target: "手动组"},
			{Rule: "MATCH", Target: "DIRECT"},
		},
	}

	cfgJSON := string(mustJson(&cfg))

	yamlText, err := ExportManualYAML(cfgJSON)
	if err != nil {
		t.Fatalf("ExportManualYAML: %v", err)
	}

	if !strings.Contains(yamlText, "proxies:") || !strings.Contains(yamlText, "proxy-groups:") {
		t.Fatalf("YAML missing sections:\n%s", yamlText)
	}

	// options must be expanded into the group mapping, not nested
	if !strings.Contains(yamlText, "interval:") || strings.Contains(yamlText, "options:") {
		t.Fatalf("group options not expanded:\n%s", yamlText)
	}

	got := mustParse(t, yamlText)

	if len(got.Proxies) != 1 {
		t.Fatalf("expected 1 proxy, got %d", len(got.Proxies))
	}

	p := got.Proxies[0]
	if p["name"] != "我的节点" || p["cipher"] != "aes-256-gcm" {
		t.Fatalf("proxy fields lost: %v", p)
	}

	extras, _ := p["extras"].(map[string]any)
	if extras == nil || extras["ws-opts"] != `{"path":"/ws"}` {
		t.Fatalf("extras lost: %v", p["extras"])
	}

	// JSON round trip renders numbers as float64; assert numerically
	port, _ := p["port"].(float64)
	if port != 443 || p["udp"] != true {
		t.Fatalf("scalar types wrong: port=%v(%T) udp=%v(%T)", p["port"], p["port"], p["udp"], p["udp"])
	}

	if len(got.ProxyGroup) != 1 {
		t.Fatalf("expected 1 group, got %d", len(got.ProxyGroup))
	}

	g := got.ProxyGroup[0]
	if g["name"] != "手动组" || g["type"] != "select" {
		t.Fatalf("group fields lost: %v", g)
	}

	opts, _ := g["options"].(map[string]any)
	if opts == nil || opts["url"] != "https://www.gstatic.com/generate_204" || opts["interval"] != "300" {
		t.Fatalf("options lost: %v", g["options"])
	}

	if len(got.Rules) != 2 {
		t.Fatalf("expected 2 rules, got %d", len(got.Rules))
	}

	if got.Rules[0].Rule != "DOMAIN-SUFFIX,example.org" || got.Rules[0].Target != "手动组" {
		t.Fatalf("rule 0 wrong: %+v", got.Rules[0])
	}

	if got.Rules[1].Rule != "MATCH" || got.Rules[1].Target != "DIRECT" {
		t.Fatalf("rule 1 wrong: %+v", got.Rules[1])
	}
}

func TestQrPayloadRoundTrip(t *testing.T) {
	cfg := ManualConfig{
		Proxies: []map[string]any{
			{"name": "节点A", "type": "ss", "server": "a.com", "port": 1, "cipher": "aes-128-gcm", "password": "p"},
		},
		Rules: []ManualRule{{Rule: "MATCH", Target: "DIRECT"}},
	}

	uri, err := EncodeManualQrPayload(string(mustJson(&cfg)))
	if err != nil {
		t.Fatalf("EncodeManualQrPayload: %v", err)
	}

	if !strings.HasPrefix(uri, "cmfa://manual?v=1&d=") {
		t.Fatalf("bad URI: %s", uri)
	}

	got := mustParse(t, uri)

	if len(got.Proxies) != 1 || got.Proxies[0]["name"] != "节点A" {
		t.Fatalf("QR round trip lost proxy: %v", got.Proxies)
	}

	if len(got.Rules) != 1 || got.Rules[0].Target != "DIRECT" {
		t.Fatalf("QR round trip lost rule: %v", got.Rules)
	}
}

func TestQrPayloadTooLarge(t *testing.T) {
	cfg := ManualConfig{}
	for i := 0; i < 60; i++ {
		// unique high-entropy fields so gzip cannot shrink the package
		nonce := fmt.Sprintf("%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x", i, i*3, i*7, i*11, i*13, i*17, i*19, i*23, i*29, i*31, i*37, i*41)
		cfg.Proxies = append(cfg.Proxies, map[string]any{
			"name": fmt.Sprintf("节点-%03d-%s", i, nonce[:12]), "type": "ss",
			"server": fmt.Sprintf("%s.example.com", nonce[:24]), "port": 44301 + i,
			"cipher": "chacha20-ietf-poly1305", "password": nonce,
			"udp": true,
		})
	}

	if _, err := EncodeManualQrPayload(string(mustJson(&cfg))); err == nil {
		t.Fatal("expected size error for large payload")
	}
}

func TestSanitizeWarningsAndStripping(t *testing.T) {
	pkg := ManualConfig{
		Proxies: []map[string]any{
			{"name": "好节点", "type": "ss", "server": "a.com", "port": 1, "cipher": "aes-128-gcm", "password": "p"},
		},
		ProxyGroup: []map[string]any{
			{
				"name": "组A", "type": "select",
				"proxies": []any{"好节点", "不存在节点", "订阅组X"},
			},
			{
				"name": "组B", "type": "select",
				"proxies": []any{"都不存在的成员"},
			},
		},
		Rules: []ManualRule{
			{Rule: "DOMAIN-SUFFIX,x.com", Target: "组A"},
			{Rule: "DOMAIN,y.com", Target: "幽灵组"},
		},
	}

	subProxies := `[{"name":"订阅节点1"}]`
	subGroups := `[{"name":"订阅组X"}]`

	out := SanitizeManualImport(string(mustJson(&pkg)), "", subProxies, subGroups)

	doc := struct {
		Result ManualImportPreview `json:"result"`
	}{}
	if err := json.Unmarshal([]byte(out), &doc); err != nil {
		t.Fatalf("preview JSON: %v", err)
	}

	r := doc.Result

	if len(r.Warnings) == 0 {
		t.Fatal("expected warnings")
	}

	// 组A: 不存在节点 stripped; 组B: dropped; rule to 幽灵组 dropped
	if r.DroppedCount != 3 {
		t.Fatalf("expected droppedCount=3, got %d (%v)", r.DroppedCount, r.Warnings)
	}

	sanitized := ManualConfig{}
	if err := json.Unmarshal(r.Sanitized, &sanitized); err != nil {
		t.Fatalf("sanitized JSON: %v", err)
	}

	if len(sanitized.Proxies) != 1 {
		t.Fatalf("expected 1 proxy kept, got %d", len(sanitized.Proxies))
	}

	if len(sanitized.ProxyGroup) != 1 || sanitized.ProxyGroup[0]["name"] != "组A" {
		t.Fatalf("expected only 组A kept: %v", sanitized.ProxyGroup)
	}

	members, _ := sanitized.ProxyGroup[0]["proxies"].([]any)
	if len(members) != 2 || members[0] != "好节点" || members[1] != "订阅组X" {
		t.Fatalf("组A members wrong: %v", members)
	}

	if len(sanitized.Rules) != 1 || sanitized.Rules[0].Target != "组A" {
		t.Fatalf("expected only 组A rule kept: %v", sanitized.Rules)
	}
}

func TestSanitizeResolvesFromLocalManual(t *testing.T) {
	pkg := ManualConfig{
		ProxyGroup: []map[string]any{
			{"name": "组A", "type": "select", "proxies": []any{"本地组"}},
		},
	}

	local := ManualConfig{
		ProxyGroup: []map[string]any{
			{"name": "本地组", "type": "select", "proxies": []any{"DIRECT"}},
		},
	}

	out := SanitizeManualImport(string(mustJson(&pkg)), string(mustJson(&local)), "", "")

	doc := struct {
		Result ManualImportPreview `json:"result"`
	}{}
	if err := json.Unmarshal([]byte(out), &doc); err != nil {
		t.Fatalf("preview JSON: %v", err)
	}

	if len(doc.Result.Warnings) != 0 {
		t.Fatalf("local manual names must resolve warnings away: %v", doc.Result.Warnings)
	}

	sanitized := ManualConfig{}
	if err := json.Unmarshal(doc.Result.Sanitized, &sanitized); err != nil {
		t.Fatal(err)
	}

	if len(sanitized.ProxyGroup) != 1 {
		t.Fatal("组A should survive")
	}
}

func TestParseYamlEmptyAndGarbage(t *testing.T) {
	if _, err := ParseManualImport("not: yaml: at: all:"); err == nil {
		t.Fatal("expected error for garbage")
	}

	if _, err := ParseManualImport("# only a comment\n"); err == nil {
		t.Fatal("expected error for empty doc")
	}

	if _, err := ParseManualImport("cmfa://manual?v=2&d=xx"); err == nil {
		t.Fatal("expected error for bad version")
	}
}

func TestParseYamlRuleFlags(t *testing.T) {
	cfg := mustParse(t, "rules:\n  - DOMAIN-SUFFIX,google.com,手动组,no-resolve\n  - IP-CIDR,1.2.3.4/32,DIRECT\n")

	if len(cfg.Rules) != 2 {
		t.Fatalf("expected 2 rules, got %d", len(cfg.Rules))
	}

	if cfg.Rules[0].Rule != "DOMAIN-SUFFIX,google.com" || cfg.Rules[0].Target != "手动组" {
		t.Fatalf("no-resolve flag not stripped: %+v", cfg.Rules[0])
	}

	if cfg.Rules[1].Target != "DIRECT" {
		t.Fatalf("plain rule wrong: %+v", cfg.Rules[1])
	}
}
