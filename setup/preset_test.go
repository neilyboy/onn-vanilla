package main

import "testing"

func TestPresetLineup(t *testing.T) {
	all := presetLineup("", nil, "")
	if len(all) < 10 {
		t.Fatalf("expected full preset, got %d groups", len(all))
	}
	for _, g := range all {
		if g.Name == "Locals" {
			t.Fatal("Locals should be dropped without a market")
		}
	}
	l := presetLineup("UK|GB", []string{"Locals", "NFL"}, "Leeds")
	if len(l) != 2 || l[0].Include != "Leeds" || !l[1].NoMerge {
		t.Fatalf("unexpected %+v", l)
	}
}
