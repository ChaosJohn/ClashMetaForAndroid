package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// Proxy overrides patch subscription-provided proxies at load time,
// without touching the subscription file. Stored per profile (keyed by
// the profile directory name, i.e. the profile UUID) so switching
// profiles never leaks overrides across.
//
// Current use: per-proxy dialer-proxy (chained dialing through another
// proxy or group). Shape: { "<proxy name>": { "dialer-proxy": "..." } }.
// Manually defined proxies are excluded from patching: they carry their
// dialer-proxy directly in the manual config form.

func proxyOverrideDir() string {
	return constant.Path.Resolve("proxy-overrides")
}

// proxyOverridePath returns the override file for one profile id.
func proxyOverridePath(profileID string) string {
	name := filepath.Base(strings.TrimSpace(profileID))

	// defense in depth: never let a crafted id escape the directory
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\\") {
		name = "_invalid_"
	}

	return filepath.Join(proxyOverrideDir(), name+".json")
}

func ReadProxyOverrides(profileID string) string {
	content, err := os.ReadFile(proxyOverridePath(profileID))
	if err != nil {
		return "{}"
	}

	return string(content)
}

func WriteProxyOverrides(profileID string, content string) {
	dir := proxyOverrideDir()

	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Warnln("Write proxy overrides: %s", err.Error())
		return
	}

	path := proxyOverridePath(profileID)

	// an empty patch set removes the file entirely
	trimmed := strings.TrimSpace(content)
	if trimmed == "" || trimmed == "{}" {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			log.Warnln("Write proxy overrides: %s", err.Error())
		}
		return
	}

	// validate before persisting so a broken payload never breaks loads
	patch := map[string]map[string]any{}
	if err := json.Unmarshal([]byte(trimmed), &patch); err != nil {
		log.Warnln("Write proxy overrides: invalid payload: %s", err.Error())
		return
	}

	if err := os.WriteFile(path, []byte(trimmed), 0o644); err != nil {
		log.Warnln("Write proxy overrides: %s", err.Error())
	}
}

// patchProxyOverrides applies stored per-proxy overrides to subscription
// proxies. Runs after patchManualConfig so manually defined proxies
// (which would have replaced same-named subscription entries anyway) and
// after patchGroupOverrides; only `dialer-proxy` is patched today.
func patchProxyOverrides(cfg *config.RawConfig, profileDir string) error {
	raw := ReadProxyOverrides(filepath.Base(profileDir))
	if strings.TrimSpace(raw) == "" {
		return nil
	}

	patch := map[string]map[string]any{}
	if err := json.Unmarshal([]byte(raw), &patch); err != nil {
		log.Warnln("Apply proxy overrides: %s", err.Error())
		return nil
	}

	if len(patch) == 0 {
		return nil
	}

	for _, p := range cfg.Proxy {
		name, _ := p["name"].(string)
		if name == "" {
			continue
		}

		over, ok := patch[name]
		if !ok || len(over) == 0 {
			continue
		}

		for k, v := range over {
			if k == "name" || k == "type" || k == "server" || k == "port" {
				// identity fields are never overridden
				continue
			}
			if s, _ := v.(string); strings.TrimSpace(s) == "" {
				// an empty string clears the field
				delete(p, k)
				continue
			}
			p[k] = v
		}

		log.Infoln("Apply proxy override to `%s`", name)
	}

	return nil
}
