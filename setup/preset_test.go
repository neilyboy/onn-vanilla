package main

import (
	"strings"
	"testing"
)

func TestPresetLineup(t *testing.T) {
	all := presetLineup(LineupOpts{}).Groups
	if len(all) < 10 {
		t.Fatalf("expected full preset, got %d groups", len(all))
	}
	for _, g := range all {
		if g.Name == "Locals" {
			t.Fatal("Locals should be dropped without a market")
		}
	}
	l := presetLineup(LineupOpts{Prefix: "UK|GB", Cats: []string{"Locals", "NFL"}, Locals: "Leeds"}).Groups
	if len(l) != 2 || l[0].Include != "Leeds" || !l[1].noMerge() {
		t.Fatalf("unexpected %+v", l)
	}
}

func TestExtrasAndCountries(t *testing.T) {
	l := presetLineup(LineupOpts{Extra: []string{"UK|GB"}, Add: []string{"DE | Sky Sports+"}, Cats: []string{"Sports"}})
	if l.Countries != "US|USA|UK|GB" || !l.DropEmptyEvents {
		t.Fatalf("countries %q drop %v", l.Countries, l.DropEmptyEvents)
	}
	names := []string{}
	for _, g := range l.Groups {
		names = append(names, g.Name)
	}
	want := []string{"Sky Sports+ · DE", "UK Sports Events", "UK Sports", "Sports", "UK TV"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Fatalf("groups %v", names)
	}
	for name, f := range map[string]bool{"DE: Sky Sport UHD": true, "UK: Sky Sports Main Event": false, "IL | Springfield | CBS": false, "ESPN 4K": false, "NBA: Lakers": false} {
		if l.foreign(name, false) != f {
			t.Errorf("foreign(%q) != %v", name, f)
		}
	}
	if !l.foreign("AR | beIN SPORTS", true) || l.foreign("UK | Sky Sports", true) {
		t.Error("group country check")
	}
	groups := []dcGroup{{ID: 1, Name: "UK | Sky Sports"}, {ID: 2, Name: "UK | Entertainment"}, {ID: 3, Name: "DE | Sky Sports+"}, {ID: 4, Name: "UK | Dazn Events"}}
	got := l.claim(groups)
	if cams := (Lineup{Groups: []LineupGroup{{Name: "Cameras", Sources: []string{"^cameras?$"}}}}).claim([]dcGroup{{ID: 9, Name: "Cameras"}}); len(cams[0]) != 1 {
		t.Error("a lineup group must be able to use the provider group with its own name")
	}
	if len(got[0]) != 1 || got[0][0].ID != 3 || got[1][0].ID != 4 || got[2][0].ID != 1 || got[4][0].ID != 2 {
		t.Fatalf("claims %+v", got)
	}
	for _, s := range []string{"NCAAF 82: NO EVENT", "NBA 11:", "NCAA Baseball 51 :", "(PDC 39) |  (2098-12-31 08:00:35)"} {
		if !reEmptyEvent.MatchString(s) {
			t.Errorf("%q should be an empty slot", s)
		}
	}
	if reEmptyEvent.MatchString("NBA 03: Lakers vs Celtics") {
		t.Error("real event treated as empty")
	}
}
