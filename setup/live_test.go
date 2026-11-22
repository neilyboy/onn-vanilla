package main

import (
	"os"
	"testing"
)

// DC_BASE=http://host:9191 DC_TOKEN=... go test -run TestLivePlan
func TestLivePlan(t *testing.T) {
	if os.Getenv("DC_TOKEN") == "" {
		t.Skip("set DC_BASE and DC_TOKEN")
	}
	c := &dcClient{base: os.Getenv("DC_BASE"), token: os.Getenv("DC_TOKEN")}
	lim, err := c.syncLimits(false)
	t.Logf("limits %+v err %v", lim, err)
	plan, _, err := c.plan(presetLineup("US|USA", []string{"locals", "sports", "news", "entertainment", "movies", "kids"}, os.Getenv("DC_LOCALS")))
	if err != nil {
		t.Fatal(err)
	}
	for _, g := range plan {
		t.Logf("%s: %d channels from %d groups", g.Name, len(g.Channels), len(g.Sources))
		for i, c := range g.Channels {
			if i < 6 || g.Name == "Locals" {
				t.Logf("   %s x%d %s", c.Name, c.Count, c.TvgID)
			}
		}
	}
}
