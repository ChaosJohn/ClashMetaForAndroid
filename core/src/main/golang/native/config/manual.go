package config

import (
	"encoding/json"
	"io"
	"net"
	"os"
	"strings"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// ManualRule is a single user-defined rule. `Rule` holds the match part
// (e.g. "DOMAIN-SUFFIX,google.com"), `Target` the policy applied on match
// (a manual group name, DIRECT or REJECT).
type ManualRule struct {
	Rule   string `json:"rule"`
	Target string `json:"target"`
}

// ManualConfig holds user-defined proxies, proxy groups and rules which are
// merged into every loaded profile at the highest priority.
type ManualConfig struct {
	Proxies    []map[string]any `json:"proxies"`
	ProxyGroup []map[string]any `json:"proxy-groups"`
	Rules      []ManualRule     `json:"rules"`
}

const defaultManualConfig = `{}`

func manualConfigPath() string {
	return constant.Path.Resolve("manual.json")
}

func ReadManualConfig() string {
	file, err := os.OpenFile(manualConfigPath(), os.O_RDONLY, 0600)
	if err != nil {
		return defaultManualConfig
	}

	buf, err := io.ReadAll(file)
	_ = file.Close()

	if err != nil {
		return defaultManualConfig
	}

	content := string(buf)
	if strings.TrimSpace(content) == "" {
		return defaultManualConfig
	}

	return content
}

func WriteManualConfig(content string) {
	file, err := os.OpenFile(manualConfigPath(), os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		log.Warnln("Write manual config: %s", err.Error())
		return
	}
	defer file.Close()

	if _, err := file.Write([]byte(content)); err != nil {
		log.Warnln("Write manual config: %s", err.Error())
	}
}

func ClearManualConfig() {
	_ = os.Remove(manualConfigPath())
}

func nameOfMapping(m map[string]any) string {
	if n, ok := m["name"].(string); ok {
		return n
	}
	return ""
}

// expandExtras merges each proxy's "extras" map into the proxy mapping and
// strips the key. Extras carry type-specific mihomo fields (e.g. uuid,
// ws-opts for vless) produced by the manual proxy form. Values arrive as
// strings through the JSON round trip: JSON-looking text ("true", objects)
// is decoded to its real type, anything else is used as a plain string.
func expandExtras(proxies []map[string]any) []map[string]any {
	expanded := make([]map[string]any, 0, len(proxies))

	for _, p := range proxies {
		extras, ok := p["extras"].(map[string]any)
		if !ok {
			if _, exists := p["extras"]; exists {
				delete(p, "extras")
			}
			expanded = append(expanded, p)
			continue
		}

		delete(p, "extras")

		for k, v := range extras {
			if _, exists := p[k]; exists {
				continue
			}

			s, ok := v.(string)
			if !ok {
				log.Warnln("Apply manual config: ignored malformed extra `%s` on proxy `%s`", k, nameOfMapping(p))
				continue
			}

			value := any(s)

			var decoded any
			if err := json.Unmarshal([]byte(s), &decoded); err == nil {
				value = decoded
			}

			p[k] = value
		}

		expanded = append(expanded, p)
	}

	return expanded
}

// patchManualConfig merges the stored manual config into the raw config.
//
// - manual proxies and proxy groups are appended, entries with a duplicate
//   name from the profile replace the profile ones so that loading never
//   fails because of name conflicts.
// - manual rules are inserted BEFORE all profile rules (highest priority).
func patchManualConfig(cfg *config.RawConfig, _ string) error {
	raw := ReadManualConfig()
	if strings.TrimSpace(raw) == "" {
		return nil
	}

	manual := ManualConfig{}
	if err := json.Unmarshal([]byte(raw), &manual); err != nil {
		log.Warnln("Apply manual config: %s", err.Error())
		return nil
	}

	// bookkeeping for injecting manual entries into well-known subscription
	// selection groups (see the injection step below)
	manualGroupNames := make(map[string]bool)
	var survivingManualGroups []string
	var manualProxyNames []string

	// merge proxies
	if len(manual.Proxies) > 0 {
		manual.Proxies = expandExtras(manual.Proxies)

		// protocols without cipher/password serialize the ss form defaults
		// as empty strings; drop the leftovers so the mapping stays clean
		for _, p := range manual.Proxies {
			if s, _ := p["cipher"].(string); s == "" {
				delete(p, "cipher")
			}
			if s, _ := p["password"].(string); s == "" {
				delete(p, "password")
			}
		}

		manualNames := make(map[string]bool, len(manual.Proxies))
		for _, p := range manual.Proxies {
			if n := nameOfMapping(p); n != "" {
				manualNames[n] = true
				manualProxyNames = append(manualProxyNames, n)
			}
		}

		// drop profile proxies with the same name
		proxies := make([]map[string]any, 0, len(cfg.Proxy)+len(manual.Proxies))
		for _, p := range cfg.Proxy {
			if n := nameOfMapping(p); manualNames[n] {
				continue
			}
			proxies = append(proxies, p)
		}
		proxies = append(proxies, manual.Proxies...)
		cfg.Proxy = proxies
	}

	// merge proxy groups
	if len(manual.ProxyGroup) > 0 {
		for _, g := range manual.ProxyGroup {
			if n := nameOfMapping(g); n != "" {
				manualGroupNames[n] = true
			}
		}

		// members are resolved against every proxy and group known after
		// the merge above plus the built-in policies; groups may reference
		// each other, so resolution runs to a fixpoint: a group survives
		// when it keeps at least one member, dead references are stripped.
		// This keeps a subscription update that removes or renames nodes
		// from breaking the whole profile load.
		valid := make(map[string]bool)
		for _, p := range cfg.Proxy {
			if n := nameOfMapping(p); n != "" {
				valid[n] = true
			}
		}
		for _, g := range cfg.ProxyGroup {
			if n := nameOfMapping(g); n != "" {
				valid[n] = true
			}
		}
		for _, b := range []string{"DIRECT", "REJECT", "REJECT-DROP", "PASS", "GLOBAL"} {
			valid[b] = true
		}

		var pending []map[string]any
		pendingNames := make(map[string]bool)

		sanitized := make([]map[string]any, 0, len(manual.ProxyGroup))
		for _, g := range manual.ProxyGroup {
			n := nameOfMapping(g)
			if n == "" {
				log.Warnln("Apply manual config: drop group without name")
				continue
			}

			if _, ok := g["type"].(string); !ok {
				g["type"] = "select"
			}

			if _, ok := g["proxies"].([]any); !ok {
				log.Warnln("Apply manual config: drop group `%s`: missing proxies", n)
				continue
			}

			pending = append(pending, g)
			pendingNames[n] = true
		}

		// resolve to a fixpoint: a group finalizes when every member is a
		// known proxy / built-in / already finalized group; groups keeping
		// only unresolved sibling refs wait another round; a group with no
		// surviving members is dropped, which in turn invalidates the refs
		// pointing at it. Terminates because every round finalizes or drops
		// at least one group, otherwise nothing changes and the loop ends.
		for progress := true; progress && len(pending) > 0; {
			progress = false

			var rest []map[string]any

			for _, g := range pending {
				n := nameOfMapping(g)
				raw, _ := g["proxies"].([]any)

				kept := make([]any, 0, len(raw))
				unresolved := false

				for _, m := range raw {
					s, ok := m.(string)
					if !ok || s == "" || s == n {
						continue
					}

					switch {
					case valid[s]:
						kept = append(kept, s)
					case pendingNames[s]:
						kept = append(kept, s)
						unresolved = true
					default:
						// dead reference, strip it
					}
				}

				switch {
				case unresolved:
					// wait for siblings to resolve, re-check next round
					g["proxies"] = kept
					rest = append(rest, g)

				case len(kept) == 0:
					log.Warnln("Apply manual config: drop group `%s`: no valid members", n)
					delete(pendingNames, n)
					progress = true

				default:
					g["proxies"] = kept
					valid[n] = true
					delete(pendingNames, n)
					sanitized = append(sanitized, g)
					survivingManualGroups = append(survivingManualGroups, n)
					progress = true
				}
			}

			pending = rest
		}

		for _, g := range pending {
			log.Warnln("Apply manual config: drop group `%s`: unresolvable member references", nameOfMapping(g))
		}

		if len(sanitized) > 0 {
			groups := make([]map[string]any, 0, len(cfg.ProxyGroup)+len(sanitized))
			for _, g := range cfg.ProxyGroup {
				if n := nameOfMapping(g); manualGroupNames[n] {
					continue
				}
				groups = append(groups, g)
			}
			groups = append(groups, sanitized...)
			cfg.ProxyGroup = groups

			lastManualGroupNames = make(map[string]bool, len(survivingManualGroups))
			for _, n := range survivingManualGroups {
				lastManualGroupNames[n] = true
			}
		}
	}

	// inject manual entries into every subscription-provided group so that
	// users can pick them without editing the subscription. Within each
	// group the member order becomes: manual groups, remaining subscription
	// groups, manual proxies, remaining subscription proxies; non-group/
	// non-proxy members (DIRECT, REJECT, ...) keep their place at the tail.
	// survivingManualGroups is in sanitizer resolution order, so the
	// injection order is rebuilt from the configured order of the manual
	// groups, restricted to the ones that survived sanitization.
	live := make(map[string]bool, len(survivingManualGroups))
	for _, n := range survivingManualGroups {
		live[n] = true
	}
	injectionGroups := make([]string, 0, len(manual.ProxyGroup))
	for _, g := range manual.ProxyGroup {
		if n := nameOfMapping(g); live[n] {
			injectionGroups = append(injectionGroups, n)
		}
	}
	injectManualEntries(cfg, manualGroupNames, injectionGroups, manualProxyNames)

	// merge rules at highest priority
	if len(manual.Rules) > 0 {
		rules := make([]string, 0, len(manual.Rules)+len(cfg.Rule))
		for _, r := range manual.Rules {
			rule := strings.TrimSpace(r.Rule)
			if rule == "" {
				continue
			}

			target := strings.TrimSpace(r.Target)
			if target != "" {
				rule = rule + "," + target
			}

			rules = append(rules, rule)
		}

		cfg.Rule = append(rules, cfg.Rule...)
	}

	// manual proxy server domains must never resolve to fake-ip, otherwise
	// the ss dialer would connect to a fake address and time out. Only
	// blacklist mode (the default) uses the filter to bypass fake-ip.
	// Domains are validated first: an invalid filter entry aborts the whole
	// profile load, so malformed servers are skipped instead.
	if len(manual.Proxies) > 0 &&
		cfg.DNS.FakeIPFilterMode == C.FilterBlackList {
		for _, p := range manual.Proxies {
			server, _ := p["server"].(string)
			server = strings.TrimSpace(server)
			if server == "" || net.ParseIP(server) != nil || !validDomain(server) {
				continue
			}

			filter := "+." + server

			exists := false
			for _, f := range cfg.DNS.FakeIPFilter {
				if f == filter {
					exists = true
					break
				}
			}
			if !exists {
				cfg.DNS.FakeIPFilter = append(cfg.DNS.FakeIPFilter, filter)
			}
		}
	}

	return nil
}

// injectManualEntries reorders the member list of matching subscription
// groups so manual groups come first, then subscription groups, manual
// proxies and subscription proxies; non-proxy members (DIRECT, REJECT,
// policy names, ...) keep their relative order at the tail.
func injectManualEntries(cfg *config.RawConfig, manualGroupNames map[string]bool, survivingManualGroups []string, manualProxyNames []string) {
	if len(survivingManualGroups) == 0 && len(manualProxyNames) == 0 {
		return
	}

	// name sets for classifying existing members
	subGroupNames := make(map[string]bool, len(cfg.ProxyGroup))
	subProxyNames := make(map[string]bool, len(cfg.Proxy))
	for _, p := range cfg.Proxy {
		if n := nameOfMapping(p); n != "" {
			subProxyNames[n] = true
		}
	}
	for _, g := range cfg.ProxyGroup {
		if n := nameOfMapping(g); n != "" {
			subGroupNames[n] = true
		}
	}
	manualProxySet := make(map[string]bool, len(manualProxyNames))
	for _, n := range manualProxyNames {
		manualProxySet[n] = true
	}

	// manual groups no longer referenced by the injection (dropped by the
	// sanitizer above) must not be injected
	liveManualGroups := make(map[string]bool, len(survivingManualGroups))
	for _, n := range survivingManualGroups {
		liveManualGroups[n] = true
	}

	for _, g := range cfg.ProxyGroup {
		name := nameOfMapping(g)
		if manualGroupNames[name] {
			continue
		}

		raw, _ := g["proxies"].([]any)
		if len(raw) == 0 {
			continue
		}

		var manualGroups, subGroups, manualProxies, subProxies, rest []any
		seen := make(map[string]bool, len(raw))

		// manual entries are injected at the front of their bucket; ones
		// already referenced by the subscription keep that membership,
		// everything else is added dynamically
		for _, n := range survivingManualGroups {
			if !seen[n] {
				seen[n] = true
				manualGroups = append(manualGroups, n)
			}
		}
		for _, n := range manualProxyNames {
			if !seen[n] {
				seen[n] = true
				manualProxies = append(manualProxies, n)
			}
		}

		// existing members keep their relative order within their bucket
		for _, m := range raw {
			s, ok := m.(string)
			if !ok || s == "" || seen[s] {
				continue
			}
			seen[s] = true
			switch {
			case manualGroupNames[s] && liveManualGroups[s]:
				manualGroups = append(manualGroups, s)
			case manualGroupNames[s]:
				// manual group was dropped by the sanitizer, skip
			case subGroupNames[s]:
				subGroups = append(subGroups, s)
			case manualProxySet[s]:
				manualProxies = append(manualProxies, s)
			case subProxyNames[s]:
				subProxies = append(subProxies, s)
			default:
				// DIRECT, REJECT, policy names, ... stay at the tail
				rest = append(rest, s)
			}
		}

		proxies := make([]any, 0, len(raw))
		proxies = append(proxies, manualGroups...)
		proxies = append(proxies, subGroups...)
		proxies = append(proxies, manualProxies...)
		proxies = append(proxies, subProxies...)
		proxies = append(proxies, rest...)

		if !sameStrings(proxies, raw) {
			g["proxies"] = proxies
		}
	}
}

// sameStrings reports whether the raw member list equals the given string
// slice element-wise.
func sameStrings(raw []any, list []any) bool {
	if len(raw) != len(list) {
		return false
	}
	for i := range raw {
		rs, ok1 := raw[i].(string)
		ls, ok2 := list[i].(string)
		if !ok1 || !ok2 || rs != ls {
			return false
		}
	}
	return true
}

// validDomain reports whether s is a plain hostname usable in a
// fake-ip-filter entry ("+." + s). Invalid entries would abort the whole
// profile load, so callers must skip them.
func validDomain(s string) bool {
	if len(s) == 0 || len(s) > 253 {
		return false
	}

	for _, label := range strings.Split(s, ".") {
		if label == "" || len(label) > 63 {
			return false
		}

		for i := 0; i < len(label); i++ {
			c := label[i]
			if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' {
				continue
			}
			return false
		}
	}

	return true
}
