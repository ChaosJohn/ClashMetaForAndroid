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

// Group overrides patch the type and tuning options of
// subscription-provided proxy groups at load time, without touching the
// subscription file. Stored per profile (keyed by the profile directory
// name, i.e. the profile UUID) so switching profiles never leaks
// overrides across.
//
// Shape: { "<group name>": { "type": "url-test", "url": "...", ... }, ... }
// Members are never overridden, only the type and its options.

func groupOverrideDir() string {
	return constant.Path.Resolve("group-overrides")
}

// groupOverridePath returns the override file for one profile id.
func groupOverridePath(profileID string) string {
	name := filepath.Base(strings.TrimSpace(profileID))

	// defense in depth: never let a crafted id escape the directory
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\\") {
		name = "_invalid_"
	}

	return filepath.Join(groupOverrideDir(), name+".json")
}

func ReadGroupOverrides(profileID string) string {
	content, err := os.ReadFile(groupOverridePath(profileID))
	if err != nil {
		return "{}"
	}

	return string(content)
}

func WriteGroupOverrides(profileID string, content string) {
	dir := groupOverrideDir()

	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Warnln("Write group overrides: %s", err.Error())
		return
	}

	path := groupOverridePath(profileID)

	// an empty patch set removes the file entirely
	trimmed := strings.TrimSpace(content)
	if trimmed == "" || trimmed == "{}" {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			log.Warnln("Write group overrides: %s", err.Error())
		}
		return
	}

	// validate before persisting so a broken payload never breaks loads
	patch := map[string]map[string]any{}
	if err := json.Unmarshal([]byte(trimmed), &patch); err != nil {
		log.Warnln("Write group overrides: invalid payload: %s", err.Error())
		return
	}

	if err := os.WriteFile(path, []byte(trimmed), 0o644); err != nil {
		log.Warnln("Write group overrides: %s", err.Error())
	}
}

func ClearGroupOverrides(profileID string) {
	if err := os.Remove(groupOverridePath(profileID)); err != nil && !os.IsNotExist(err) {
		log.Warnln("Clear group overrides: %s", err.Error())
	}
}

// lastManualGroupNames carries the manual group names resolved during the
// patchManualConfig processor run of the same load, so group type
// overrides can skip groups that a manual config already replaced. The
// processor chain runs serially under the service process lock.
var lastManualGroupNames map[string]bool

// patchGroupOverrides applies stored per-profile type overrides to
// subscription proxy groups. Runs after patchManualConfig so manually
// defined groups (persisted directly in the manual config) are never
// double-patched.
func patchGroupOverrides(cfg *config.RawConfig, profileDir string) error {
	raw := ReadGroupOverrides(filepath.Base(profileDir))
	if strings.TrimSpace(raw) == "" {
		return nil
	}

	patch := map[string]map[string]any{}
	if err := json.Unmarshal([]byte(raw), &patch); err != nil {
		log.Warnln("Apply group overrides: %s", err.Error())
		return nil
	}

	if len(patch) == 0 {
		return nil
	}

	for _, g := range cfg.ProxyGroup {
		name, _ := g["name"].(string)
		if name == "" || lastManualGroupNames[name] {
			continue
		}

		p, ok := patch[name]
		if !ok || len(p) == 0 {
			continue
		}

		for k, v := range p {
			if k == "proxies" {
				// never override membership
				continue
			}
			g[k] = v
		}

		if t, _ := g["type"].(string); t == "select" {
			// back to a plain selector: drop tuning options the group
			// type does not use, mihomo ignores them but stay clean
			delete(g, "url")
			delete(g, "interval")
			delete(g, "tolerance")
			delete(g, "strategy")
		}

		log.Infoln("Apply group override to `%s`", name)
	}

	return nil
}
