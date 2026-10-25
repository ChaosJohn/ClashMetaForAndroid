package tunnel

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// groupTestInfo is the subset of a proxy group's JSON output which describes
// how the group tests its nodes. Alive states are stored per test URL, so
// probes must use the group's own test URL to update the state that the group
// actually reads when picking a node.
type groupTestInfo struct {
	TestURL        string `json:"testUrl"`
	ExpectedStatus string `json:"expectedStatus"`
}

const (
	// selectedProbeTimeout matches mihomo's per-probe timeout in Fallback.Set.
	selectedProbeTimeout = 5 * time.Second
	// groupURLTestTimeout bounds the concurrent URL test of a whole group.
	groupURLTestTimeout = 30 * time.Second
)

func parseGroupTestInfo(g outboundgroup.ProxyGroup) (groupTestInfo, bool) {
	raw, err := g.MarshalJSON()
	if err != nil {
		return groupTestInfo{}, false
	}

	var info groupTestInfo
	if err := json.Unmarshal(raw, &info); err != nil {
		return groupTestInfo{}, false
	}

	return info, true
}

// probeWithGroupURL re-tests one proxy against the given group test URL,
// refreshing the alive state stored under that URL key.
func probeWithGroupURL(p C.Proxy, info groupTestInfo, timeout time.Duration) bool {
	expectedStatus, _ := utils.NewUnsignedRanges[uint16](info.ExpectedStatus)

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	_, _ = p.URLTest(ctx, info.TestURL, expectedStatus)

	return p.AliveForTestUrl(info.TestURL)
}

// HealthCheck refreshes the alive states of the named group's providers.
func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

// fullHealthCheck refreshes the alive states of every member of the group
// under the group's test URL (covering static `proxies:` members which no
// provider owns), plus the providers' own health checks.
func fullHealthCheck(g outboundgroup.ProxyGroup) {
	if info, ok := parseGroupTestInfo(g); ok && info.TestURL != "" {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), groupURLTestTimeout)
			defer cancel()

			_, _ = g.URLTest(ctx, info.TestURL, nil)
		}()
	}

	HealthCheck(g.Name())
}

// mainChainAlive checks the cached alive state of the main proxy chain (the
// same chain shown in the status notification) from the outermost group down
// to the concrete node. Every hop must be alive under the hop group's own test
// URL. When a hop's cached state is dead, the hop is re-tested once to rule
// out a stale cache. The result of that re-test also refreshes the group's
// selection (e.g. fallback switching to its next alive node).
func mainChainAlive() bool {
	groups, final := resolveMainChain()

	// No main chain (Direct mode or plain node subscription): nothing to
	// shortcut on, behave like the old full check.
	if len(groups) == 0 {
		return false
	}

	for _, g := range groups {
		info, ok := parseGroupTestInfo(g)
		if !ok {
			return false
		}

		if info.TestURL == "" {
			continue
		}

		now := g.Now()

		var selected C.Proxy
		for _, p := range g.Proxies() {
			if p.Name() == now {
				selected = p
				break
			}
		}

		if selected == nil {
			return false
		}

		if selected.AliveForTestUrl(info.TestURL) {
			log.Debugln("Health check: `%s` alive in group %s, skip", now, g.Name())
			continue
		}

		if !probeWithGroupURL(selected, info, selectedProbeTimeout) {
			log.Debugln("Health check: `%s` dead in group %s, full check required", now, g.Name())
			return false
		}
	}

	if final != nil {
		log.Debugln("Health check: main chain `%s` alive, all groups skipped", final.Name())
	}

	return true
}

func HealthCheckAll() {
	if mainChainAlive() {
		return
	}

	for _, g := range QueryProxyGroupNames(false) {
		go func(name string) {
			p := tunnel.Proxies()[name]
			if p == nil {
				return
			}

			g, ok := p.Adapter().(outboundgroup.ProxyGroup)
			if !ok {
				return
			}

			fullHealthCheck(g)
		}(g)
	}
}
