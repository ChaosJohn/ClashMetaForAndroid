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

	// merge proxies
	if len(manual.Proxies) > 0 {
		manualNames := make(map[string]bool, len(manual.Proxies))
		for _, p := range manual.Proxies {
			if n := nameOfMapping(p); n != "" {
				manualNames[n] = true
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
		manualGroupNames := make(map[string]bool, len(manual.ProxyGroup))
		for _, g := range manual.ProxyGroup {
			if n := nameOfMapping(g); n != "" {
				manualGroupNames[n] = true
			}
		}

		groups := make([]map[string]any, 0, len(cfg.ProxyGroup)+len(manual.ProxyGroup))
		for _, g := range cfg.ProxyGroup {
			if n := nameOfMapping(g); manualGroupNames[n] {
				continue
			}
			groups = append(groups, g)
		}
		groups = append(groups, manual.ProxyGroup...)
		cfg.ProxyGroup = groups
	}

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
	if len(manual.Proxies) > 0 &&
		cfg.DNS.FakeIPFilterMode == C.FilterBlackList {
		for _, p := range manual.Proxies {
			server, _ := p["server"].(string)
			server = strings.TrimSpace(server)
			if server == "" || net.ParseIP(server) != nil {
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
