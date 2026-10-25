package config

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/log"
)

// This file implements import/export of the manual configuration
// (ManualConfig): YAML serialization for file exchange, QR payload
// encoding/decoding for on-device sharing, and reference sanitization with
// warnings for the import preview dialog.
//
// All functions are pure over their string inputs; they never touch the
// stored manual config. The app drives the flow: query current config, parse
// the import, show a preview, then persist via the regular patchManualConfig
// (replace = patch with the sanitized package, merge = patch with the merged
// package built by the app).

// expandGroupOptions merges each group's "options" map into the group mapping
// and strips the key, mirroring expandExtras for proxies. The manual group
// form serializes tuning options (url, interval, strategy, ...) into this
// nested map, while mihomo expects them as plain mapping keys.
func expandGroupOptions(groups []map[string]any) []map[string]any {
	expanded := make([]map[string]any, 0, len(groups))

	for _, g := range groups {
		options, ok := g["options"].(map[string]any)
		if !ok {
			if _, exists := g["options"]; exists {
				delete(g, "options")
			}
			expanded = append(expanded, g)
			continue
		}

		delete(g, "options")

		for k, v := range options {
			if _, exists := g[k]; exists {
				continue
			}

			s, ok := v.(string)
			if !ok {
				log.Warnln("Apply manual config: ignored malformed option `%s` on group `%s`", k, nameOfMapping(g))
				continue
			}

			value := any(s)

			var decoded any
			if err := json.Unmarshal([]byte(s), &decoded); err == nil {
				value = decoded
			}

			g[k] = value
		}

		expanded = append(expanded, g)
	}

	return expanded
}

// ExportManualYAML renders the given manual config JSON as a mihomo-style
// YAML snippet (proxies: / proxy-groups: / rules:). Group options and proxy
// extras are expanded to plain mapping keys. An empty config yields an
// almost-empty document so the export never fails.
func ExportManualYAML(cfgJSON string) (string, error) {
	cfg := ManualConfig{}
	if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
		return "", err
	}

	proxies := expandExtras(cfg.Proxies)
	groups := expandGroupOptions(cfg.ProxyGroup)

	// protocols without cipher/password serialize the ss form defaults as
	// empty strings; drop the leftovers so the snippet stays clean
	for _, p := range proxies {
		if s, _ := p["cipher"].(string); s == "" {
			delete(p, "cipher")
		}
		if s, _ := p["password"].(string); s == "" {
			delete(p, "password")
		}
	}

	// rules export as plain "TYPE,MATCH,TARGET" strings (the mihomo snippet
	// style users expect), not as structured mappings
	rules := make([]string, 0, len(cfg.Rules))
	for _, r := range cfg.Rules {
		rule := strings.TrimSpace(r.Rule)
		target := strings.TrimSpace(r.Target)

		if rule == "" {
			continue
		}

		if target != "" {
			rule = rule + "," + target
		}

		rules = append(rules, rule)
	}

	doc := struct {
		Proxies    []map[string]any `yaml:"proxies,omitempty"`
		ProxyGroup []map[string]any `yaml:"proxy-groups,omitempty"`
		Rules      []string         `yaml:"rules,omitempty"`
	}{proxies, groups, rules}

	out, err := yaml.Marshal(&doc)
	if err != nil {
		return "", err
	}

	var buf bytes.Buffer
	buf.WriteString("# Exported by ClashMetaForAndroid (manual config)\n")
	buf.Write(out)

	return buf.String(), nil
}

// ParseManualImport normalizes external input into ManualConfig JSON. YAML
// (mihomo snippet) and cmfa://manual URIs (QR payloads) are accepted; the
// format is detected from the payload shape. Unknown fields fold into
// extras/options so persistence round-trips losslessly.
func ParseManualImport(text string) (string, error) {
	trimmed := strings.TrimSpace(text)

	// QR payload: cmfa://manual?v=1&d=<gzip+base64url>
	if strings.HasPrefix(trimmed, "cmfa://manual") {
		uri, err := url.Parse(trimmed)
		if err != nil {
			return "", fmt.Errorf("invalid cmfa link: %w", err)
		}

		if uri.Host != "manual" {
			return "", fmt.Errorf("invalid cmfa link: unexpected host %q", uri.Host)
		}

		if v := uri.Query().Get("v"); v != "" && v != "1" {
			return "", fmt.Errorf("unsupported cmfa link version: %s", v)
		}

		data := uri.Query().Get("d")
		if data == "" {
			return "", fmt.Errorf("invalid cmfa link: missing payload")
		}

		return decodeManualQrPayload(data)
	}

	// otherwise: YAML snippet
	return parseManualYAML([]byte(trimmed))
}

// parseManualYAML decodes a YAML snippet into normalized ManualConfig JSON.
func parseManualYAML(data []byte) (string, error) {
	doc := struct {
		Proxies    []map[string]any `yaml:"proxies"`
		ProxyGroup []map[string]any `yaml:"proxy-groups"`
		Rules      []any            `yaml:"rules"`
	}{}

	if err := yaml.Unmarshal(data, &doc); err != nil {
		return "", fmt.Errorf("invalid YAML: %w", err)
	}

	if doc.Proxies == nil && doc.ProxyGroup == nil && doc.Rules == nil {
		return "", fmt.Errorf("no proxies/proxy-groups/rules found")
	}

	// drop malformed entries early so normalization can assume maps
	proxies := make([]map[string]any, 0, len(doc.Proxies))
	for _, p := range doc.Proxies {
		if p != nil {
			proxies = append(proxies, p)
		}
	}

	groups := make([]map[string]any, 0, len(doc.ProxyGroup))
	for _, g := range doc.ProxyGroup {
		if g != nil {
			groups = append(groups, g)
		}
	}

	normalizeProxies(proxies)
	normalizeGroups(groups)

	cfg := ManualConfig{
		Proxies:    proxies,
		ProxyGroup: groups,
		Rules:      parseYamlRules(doc.Rules),
	}

	out, err := json.Marshal(&cfg)
	if err != nil {
		return "", err
	}

	return string(out), nil
}

// parseYamlRules converts YAML rule strings ("TYPE,MATCH,TARGET[,flags]")
// into ManualRule entries. The stored model joins rule+target with a comma
// when loading, so flags riding after the target cannot be represented and
// are dropped with a warning.
func parseYamlRules(raw []any) []ManualRule {
	rules := make([]ManualRule, 0, len(raw))

	for _, r := range raw {
		s, ok := r.(string)
		if !ok {
			log.Warnln("Manual import: ignore non-string rule entry")
			continue
		}

		// strip a trailing YAML comment appended by the exporter
		if idx := strings.Index(s, " #"); idx >= 0 {
			s = s[:idx]
		}

		parts := strings.Split(strings.TrimSpace(s), ",")
		if len(parts) < 2 {
			log.Warnln("Manual import: ignore malformed rule `%s`", s)
			continue
		}

		target := strings.TrimSpace(parts[len(parts)-1])

		if target == "no-resolve" || target == "src" {
			log.Warnln("Manual import: drop flag `%s` of rule `%s`", target, s)
			parts = parts[:len(parts)-1]

			if len(parts) < 2 {
				log.Warnln("Manual import: ignore malformed rule `%s`", s)
				continue
			}

			target = strings.TrimSpace(parts[len(parts)-1])
		}

		rules = append(rules, ManualRule{
			Rule:   strings.Join(parts[:len(parts)-1], ","),
			Target: target,
		})
	}

	return rules
}

var knownProxyKeys = map[string]bool{
	"name": true, "type": true, "server": true, "port": true,
	"password": true, "cipher": true, "udp": true, "extras": true,
}

var knownGroupKeys = map[string]bool{
	"name": true, "type": true, "proxies": true, "options": true,
}

// stringifyYamlValue renders a YAML-decoded value the way the stored model
// keeps it: strings stay bare, everything else becomes its JSON text.
func stringifyYamlValue(v any) string {
	if s, ok := v.(string); ok {
		return s
	}

	b, err := json.Marshal(v)
	if err != nil {
		return ""
	}

	return string(b)
}

// normalizeProxies folds unknown keys into "extras" and coerces the known
// keys to the types of the Kotlin ManualProxy model, so the JSON produced
// here decodes losslessly there.
func normalizeProxies(proxies []map[string]any) {
	for _, p := range proxies {
		extras := map[string]any{}

		if raw, ok := p["extras"].(map[string]any); ok {
			for k, v := range raw {
				if s := stringifyYamlValue(v); s != "" {
					extras[k] = s
				}
			}
		}

		for k, v := range p {
			if knownProxyKeys[k] {
				continue
			}

			if s := stringifyYamlValue(v); s != "" {
				extras[k] = s
			}

			delete(p, k)
		}

		if len(extras) > 0 {
			p["extras"] = extras
		}

		if v, ok := p["port"]; ok {
			switch n := v.(type) {
			case int:
				// keep
			case int64:
				p["port"] = int(n)
			case float64:
				p["port"] = int(n)
			case string:
				if parsed, err := strconv.Atoi(strings.TrimSpace(n)); err == nil {
					p["port"] = parsed
				} else {
					delete(p, "port")
				}
			default:
				delete(p, "port")
			}
		}

		if v, ok := p["udp"]; ok {
			switch b := v.(type) {
			case bool:
				// keep
			case string:
				if parsed, err := strconv.ParseBool(strings.TrimSpace(b)); err == nil {
					p["udp"] = parsed
				} else {
					delete(p, "udp")
				}
			default:
				delete(p, "udp")
			}
		}

		for _, k := range []string{"name", "type", "server", "password", "cipher"} {
			v, ok := p[k]
			if !ok || v == nil {
				delete(p, k)
				continue
			}

			if _, ok := v.(string); !ok {
				if s := stringifyYamlValue(v); s != "" {
					p[k] = s
				} else {
					delete(p, k)
				}
			}
		}
	}
}

// normalizeGroups folds unknown keys into "options" (stringified), mirrors
// normalizeProxies for the Kotlin ManualProxyGroup model.
func normalizeGroups(groups []map[string]any) {
	for _, g := range groups {
		options := map[string]any{}

		if raw, ok := g["options"].(map[string]any); ok {
			for k, v := range raw {
				if s := stringifyYamlValue(v); s != "" {
					options[k] = s
				}
			}
		}

		for k, v := range g {
			if knownGroupKeys[k] {
				continue
			}

			if s := stringifyYamlValue(v); s != "" {
				options[k] = s
			}

			delete(g, k)
		}

		if len(options) > 0 {
			g["options"] = options
		}

		for _, k := range []string{"name", "type"} {
			v, ok := g[k]
			if !ok || v == nil {
				delete(g, k)
				continue
			}

			if _, ok := v.(string); !ok {
				if s := stringifyYamlValue(v); s != "" {
					g[k] = s
				} else {
					delete(g, k)
				}
			}
		}

		if members, ok := g["proxies"].([]any); ok {
			cleaned := make([]any, 0, len(members))
			for _, m := range members {
				if s, ok := m.(string); ok && strings.TrimSpace(s) != "" {
					cleaned = append(cleaned, s)
				}
			}
			g["proxies"] = cleaned
		} else if _, exists := g["proxies"]; exists {
			delete(g, "proxies")
		}
	}
}

// decodeManualQrPayload decodes the gzip+base64url payload of a
// cmfa://manual QR link into normalized ManualConfig JSON.
func decodeManualQrPayload(data string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(data, "="))
	if err != nil {
		return "", fmt.Errorf("invalid cmfa link payload: %w", err)
	}

	zr, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		return "", fmt.Errorf("invalid cmfa link payload: %w", err)
	}

	content, err := io.ReadAll(zr)
	if err != nil {
		return "", fmt.Errorf("invalid cmfa link payload: %w", err)
	}

	cfg := ManualConfig{}
	if err := json.Unmarshal(content, &cfg); err != nil {
		return "", fmt.Errorf("invalid manual config: %w", err)
	}

	normalizeProxies(cfg.Proxies)
	normalizeGroups(cfg.ProxyGroup)

	out, err := json.Marshal(&cfg)
	if err != nil {
		return "", err
	}

	return string(out), nil
}

// maxQrPayloadBytes caps the QR URI length. QR version 40 in byte mode with
// ECC L holds 2953 bytes; beyond that phone cameras stop scanning reliably.
const maxQrPayloadBytes = 2900

// EncodeManualQrPayload builds a cmfa://manual QR URI from ManualConfig
// JSON. Returns an error when the config is empty or the encoded payload
// exceeds maxQrPayloadBytes (use file export instead).
func EncodeManualQrPayload(cfgJSON string) (string, error) {
	cfg := ManualConfig{}
	if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
		return "", err
	}

	if len(cfg.Proxies)+len(cfg.ProxyGroup)+len(cfg.Rules) == 0 {
		return "", fmt.Errorf("manual config is empty")
	}

	content, err := json.Marshal(&cfg)
	if err != nil {
		return "", err
	}

	var buf bytes.Buffer
	zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)

	if _, err := zw.Write(content); err != nil {
		return "", err
	}

	if err := zw.Close(); err != nil {
		return "", err
	}

	payload := base64.RawURLEncoding.EncodeToString(buf.Bytes())
	uri := "cmfa://manual?v=1&d=" + payload

	if len(uri) > maxQrPayloadBytes {
		return "", fmt.Errorf("payload too large for QR (%d bytes), use file export", len(uri))
	}

	return uri, nil
}

// ---------------------------------------------------------------------------
// Reference sanitization
// ---------------------------------------------------------------------------

// ManualImportItem is one import entry for the preview dialog.
type ManualImportItem struct {
	Name string `json:"name"`
	Type string `json:"type"`
	OK   bool   `json:"ok"`
}

// ManualImportPreview is the preview document returned to Kotlin: per-item
// status, global warnings and the sanitized package ready for persistence.
type ManualImportPreview struct {
	Proxies      []ManualImportItem `json:"proxies"`
	ProxyGroups  []ManualImportItem `json:"proxyGroups"`
	Rules        []ManualImportItem `json:"rules"`
	Warnings     []string           `json:"warnings"`
	DroppedCount int                `json:"droppedCount"`
	Sanitized    json.RawMessage    `json:"sanitized"`
}

// knownNames collects the universe of resolvable names: built-in policies +
// import package + local manual config + subscription proxies/groups (given
// as JSON arrays of name-bearing mappings).
func knownNames(pkg *ManualConfig, localJSON, subProxiesJSON, subGroupsJSON string) map[string]bool {
	names := make(map[string]bool)

	for _, b := range []string{"DIRECT", "REJECT", "REJECT-DROP", "PASS", "GLOBAL"} {
		names[b] = true
	}

	for _, p := range pkg.Proxies {
		if n := nameOfMapping(p); n != "" {
			names[n] = true
		}
	}
	for _, g := range pkg.ProxyGroup {
		if n := nameOfMapping(g); n != "" {
			names[n] = true
		}
	}

	collect := func(jsonDoc, kind string) {
		if strings.TrimSpace(jsonDoc) == "" {
			return
		}

		var list []map[string]any
		if err := json.Unmarshal([]byte(jsonDoc), &list); err != nil {
			log.Warnln("Manual import: skip unreadable %s list: %s", kind, err.Error())
			return
		}

		for _, m := range list {
			if n := nameOfMapping(m); n != "" {
				names[n] = true
			}
		}
	}

	// local manual config is a full ManualConfig document
	if strings.TrimSpace(localJSON) != "" {
		local := ManualConfig{}
		if err := json.Unmarshal([]byte(localJSON), &local); err != nil {
			log.Warnln("Manual import: skip unreadable local manual config: %s", err.Error())
		} else {
			for _, p := range local.Proxies {
				if n := nameOfMapping(p); n != "" {
					names[n] = true
				}
			}
			for _, g := range local.ProxyGroup {
				if n := nameOfMapping(g); n != "" {
					names[n] = true
				}
			}
		}
	}

	collect(subProxiesJSON, "proxy")
	collect(subGroupsJSON, "group")

	return names
}

// SanitizeManualImport validates an import package against the universe of
// known names and returns the preview JSON: per-item status, warnings and a
// sanitized copy of the package (dangling members stripped, dead rules
// dropped). The sanitized package is what the app persists on confirmation.
func SanitizeManualImport(cfgJSON, localJSON, subProxiesJSON, subGroupsJSON string) string {
	pkg := ManualConfig{}
	if err := json.Unmarshal([]byte(cfgJSON), &pkg); err != nil {
		return marshalError(err)
	}

	names := knownNames(&pkg, localJSON, subProxiesJSON, subGroupsJSON)

	preview := ManualImportPreview{
		Proxies:     []ManualImportItem{},
		ProxyGroups: []ManualImportItem{},
		Rules:       []ManualImportItem{},
		Warnings:    []string{},
	}

	keptProxies := make([]map[string]any, 0, len(pkg.Proxies))
	for _, p := range pkg.Proxies {
		name := nameOfMapping(p)
		ptype, _ := p["type"].(string)

		ok := name != "" && ptype != ""

		preview.Proxies = append(preview.Proxies, ManualImportItem{Name: name, Type: ptype, OK: ok})

		if ok {
			keptProxies = append(keptProxies, p)
		} else {
			preview.DroppedCount++
			preview.Warnings = append(preview.Warnings, fmt.Sprintf("代理「%s」缺少名称或类型，将被剔除", name))
		}
	}

	keptGroups := make([]map[string]any, 0, len(pkg.ProxyGroup))
	for _, g := range pkg.ProxyGroup {
		name := nameOfMapping(g)
		gtype, _ := g["type"].(string)
		if gtype == "" {
			gtype = "select"
		}

		members, _ := g["proxies"].([]any)

		kept := make([]any, 0, len(members))
		var dropped []string

		for _, m := range members {
			s, ok := m.(string)
			if !ok || s == "" || s == name {
				continue
			}

			if names[s] {
				kept = append(kept, s)
			} else {
				dropped = append(dropped, s)
			}
		}

		if len(dropped) > 0 {
			preview.Warnings = append(preview.Warnings,
				fmt.Sprintf("组「%s」成员 %s 无法解析，导入后剔除", name, strings.Join(dropped, "、")))
			preview.DroppedCount += len(dropped)
		}

		ok := name != "" && len(kept) > 0

		if name != "" && len(kept) == 0 {
			preview.Warnings = append(preview.Warnings,
				fmt.Sprintf("组「%s」没有可解析成员，整组剔除", name))
		}

		preview.ProxyGroups = append(preview.ProxyGroups, ManualImportItem{Name: name, Type: gtype, OK: ok})

		if ok {
			g["proxies"] = kept
			if t, exists := g["type"]; !exists || t == "" {
				g["type"] = "select"
			}
			keptGroups = append(keptGroups, g)
		}
	}

	keptRules := make([]ManualRule, 0, len(pkg.Rules))
	for _, r := range pkg.Rules {
		rule := strings.TrimSpace(r.Rule)
		target := strings.TrimSpace(r.Target)

		ok := rule != "" && target != "" && names[target]

		preview.Rules = append(preview.Rules, ManualImportItem{Name: rule, Type: target, OK: ok})

		if !ok {
			preview.DroppedCount++
			preview.Warnings = append(preview.Warnings,
				fmt.Sprintf("规则「%s,%s」目标无法解析，整条剔除", rule, target))
			continue
		}

		keptRules = append(keptRules, r)
	}

	sanitizedJSON, err := json.Marshal(&ManualConfig{
		Proxies:    keptProxies,
		ProxyGroup: keptGroups,
		Rules:      keptRules,
	})
	if err != nil {
		return marshalError(err)
	}

	preview.Sanitized = sanitizedJSON

	out, err := json.Marshal(&struct {
		Result ManualImportPreview `json:"result"`
	}{preview})
	if err != nil {
		return marshalError(err)
	}

	return string(out)
}

func marshalError(err error) string {
	out, _ := json.Marshal(map[string]string{"error": err.Error()})
	return string(out)
}
