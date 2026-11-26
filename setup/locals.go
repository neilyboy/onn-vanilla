package main

import (
	"encoding/json"
	"errors"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// Local stations are found from the providers' own stream names, so no call letters or market list is needed:
// "US ABC 25 (WEEK-DT2) Peoria" or "IL | Peoria | CBS WMBD" give a call sign and its city.
var (
	reLocalParen = regexp.MustCompile(`\(([KW][A-Z]{2,3})(?:-[A-Z0-9]+)?\)\s*(.+)$`)
	reNetwork    = regexp.MustCompile(`(?i)\b(ABC|CBS|NBC|FOX|PBS|CW|MY)\b`)
	reLocalPipe  = regexp.MustCompile(`^([A-Z]{2})\s*\|\s*([^|]+?)\s*\|\s*.*\b([KW][A-Z]{2,3})(?:-[A-Z0-9]+)?\s*$`)
)

type LocalStation struct {
	Name  string  `json:"name"`
	Call  string  `json:"call"`
	City  string  `json:"city"`
	Miles float64 `json:"miles"`
}

type LocalsResult struct {
	Place    string         `json:"place"`
	Include  string         `json:"include"`
	Stations []LocalStation `json:"stations"`
}

type latLon struct{ Lat, Lon float64 }

type geoHit struct {
	Lat, Lon float64
	Pop      int
}

func milesBetween(a, b latLon) float64 {
	const r = 3958.8
	rad := math.Pi / 180
	dLat, dLon := (b.Lat-a.Lat)*rad, (b.Lon-a.Lon)*rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(a.Lat*rad)*math.Cos(b.Lat*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Sqrt(h))
}

func zipPlace(zip string) (string, latLon, error) {
	var z struct {
		Places []struct {
			Name  string `json:"place name"`
			Lat   string `json:"latitude"`
			Lon   string `json:"longitude"`
			State string `json:"state abbreviation"`
		} `json:"places"`
	}
	if err := getJSON("https://api.zippopotam.us/us/"+zip, &z); err != nil || len(z.Places) == 0 {
		return "", latLon{}, errors.New("couldn't find that ZIP code")
	}
	p := z.Places[0]
	lat, _ := strconv.ParseFloat(p.Lat, 64)
	lon, _ := strconv.ParseFloat(p.Lon, 64)
	return p.Name + ", " + p.State, latLon{lat, lon}, nil
}

var geoMu sync.Mutex

// cityNear returns the US place called [city] that a TV market is likely named after: among places with that name
// and at least half the population of the biggest one, the closest to [home]. A small "Washington, IL" next door
// shouldn't beat Washington, DC, but Bloomington IL vs IN (similar size) is decided by distance.
func cityNear(city string, home latLon, cache map[string][]geoHit) (latLon, bool) {
	geoMu.Lock()
	hits, ok := cache[city]
	geoMu.Unlock()
	if !ok {
		var g struct {
			Results []struct {
				Lat     float64 `json:"latitude"`
				Lon     float64 `json:"longitude"`
				Country string  `json:"country_code"`
				Pop     int     `json:"population"`
			} `json:"results"`
		}
		if err := getJSON("https://geocoding-api.open-meteo.com/v1/search?count=10&countryCode=US&name="+url.QueryEscape(city), &g); err != nil {
			return latLon{}, false
		}
		for _, r := range g.Results {
			if r.Country == "US" {
				hits = append(hits, geoHit{r.Lat, r.Lon, r.Pop})
			}
		}
		geoMu.Lock()
		cache[city] = hits
		geoMu.Unlock()
	}
	top := 0
	for _, h := range hits {
		top = max(top, h.Pop)
	}
	best, found := latLon{}, false
	for _, h := range hits {
		p := latLon{h.Lat, h.Lon}
		if h.Pop*2 >= top && (!found || milesBetween(home, p) < milesBetween(home, best)) {
			best, found = p, true
		}
	}
	return best, found
}

// findLocals picks the stations whose city is nearest the ZIP (plus any within ~35 miles of that),
// and returns an include pattern of their call signs for the Locals group.
func (c *dcClient) findLocals(zip string, prefix string) (LocalsResult, error) {
	place, home, err := zipPlace(strings.TrimSpace(zip))
	if err != nil {
		return LocalsResult{}, err
	}
	if prefix == "" {
		prefix = "US|USA"
	}
	groups, err := c.groups()
	if err != nil {
		return LocalsResult{}, err
	}
	src := regexp.MustCompile(`(?i)^(` + prefix + `)\s*\|\s*(Local\b|.*Locals)`)
	var local []dcGroup
	for _, g := range groups {
		if src.MatchString(g.Name) && !regexp.MustCompile(`(?i)telemundo|univision|galavision|unimas`).MatchString(g.Name) {
			local = append(local, g)
		}
	}
	streams, err := c.streamsIn(local)
	if err != nil {
		return LocalsResult{}, err
	}
	names := make([]string, len(streams))
	for i, s := range streams {
		names[i] = s.Name
	}
	return pickLocals(names, place, home)
}

// pickLocals does the work of findLocals on a list of local-group stream names.
func pickLocals(streamNames []string, place string, home latLon) (LocalsResult, error) {
	type cand struct {
		name, call string
		cities     []string
	}
	var cands []cand
	cities := map[string]bool{}
	for _, raw := range streamNames {
		n := strings.TrimSpace(raw)
		if m := reLocalPipe.FindStringSubmatch(n); m != nil {
			city := strings.TrimSpace(m[2]) + ", " + m[1]
			cands = append(cands, cand{n, m[3], []string{city}})
			cities[city] = true
		} else if m := reLocalParen.FindStringSubmatch(n); m != nil {
			var list []string
			for _, city := range strings.Split(m[2], "/") {
				if city = strings.TrimSpace(city); city != "" {
					list = append(list, city)
					cities[city] = true
				}
			}
			if len(list) > 0 {
				cands = append(cands, cand{n, m[1], list})
			}
		}
	}
	if len(cands) == 0 {
		return LocalsResult{}, errors.New("your providers' local channel groups don't name their cities, so type your city or call letters instead")
	}
	cachePath := filepath.Join(dataDir, "geocode-cache.json")
	cache := map[string][]geoHit{}
	if b, err := os.ReadFile(cachePath); err == nil {
		_ = json.Unmarshal(b, &cache)
	}
	where := map[string]float64{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for city := range cities {
		wg.Add(1)
		go func(city string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			q := city
			if i := strings.LastIndex(city, ", "); i > 0 {
				q = city[:i]
			}
			if p, ok := cityNear(q, home, cache); ok {
				mu.Lock()
				where[city] = milesBetween(home, p)
				mu.Unlock()
			}
		}(city)
	}
	wg.Wait()
	if b, err := json.Marshal(cache); err == nil {
		_ = os.WriteFile(cachePath, b, 0o600)
	}
	// A multi-city market ("Waco/Temple/Bryan") is as far as its middle city, so one look-alike town nearby doesn't count.
	dist := func(cd cand) (float64, string, bool) {
		var ds []float64
		for _, c := range cd.cities {
			if d, ok := where[c]; ok {
				ds = append(ds, d)
			}
		}
		if len(ds) == 0 {
			return 0, "", false
		}
		sort.Float64s(ds)
		return ds[len(ds)/2], cd.cities[0], true
	}
	nearest := math.Inf(1)
	for _, cd := range cands {
		if d, _, ok := dist(cd); ok {
			nearest = math.Min(nearest, d)
		}
	}
	if math.IsInf(nearest, 1) || nearest > 150 {
		return LocalsResult{Place: place}, errors.New("no local stations found near " + place)
	}
	type pick struct {
		cd   cand
		d    float64
		city string
	}
	var picks []pick
	letters := map[string]string{}
	for _, cd := range cands {
		if d, city, ok := dist(cd); ok && d <= nearest+25 {
			picks = append(picks, pick{cd, d, city})
			letters[city] += cd.call[:1]
		}
	}
	// Same-named cities (Springfield MO, IL, MA) geocode to one spot. Call signs start with K west of the
	// Mississippi and W east of it, so when a city has both, keep the side the ZIP is on.
	side := "W"
	if home.Lon < -90 {
		side = "K"
	}
	// Keep only the closest station(s) of each network, so a second PBS or NBC from the next market over drops out.
	closest := map[string]float64{}
	for _, p := range picks {
		if n := strings.ToUpper(reNetwork.FindString(p.cd.name)); n != "" {
			if d, ok := closest[n]; !ok || p.d < d {
				closest[n] = p.d
			}
		}
	}
	res := LocalsResult{Place: place}
	seen := map[string]bool{}
	var calls []string
	for _, p := range picks {
		l := letters[p.city]
		if seen[p.cd.name] || (strings.Contains(l, "K") && strings.Contains(l, "W") && p.cd.call[:1] != side) {
			continue
		}
		if home.Lon < -100 && p.cd.call[:1] == "W" {
			continue
		}
		if n := strings.ToUpper(reNetwork.FindString(p.cd.name)); n != "" && p.d > closest[n]+5 {
			continue
		}
		seen[p.cd.name] = true
		res.Stations = append(res.Stations, LocalStation{p.cd.name, p.cd.call, p.city, math.Round(p.d)})
		if !contains(calls, p.cd.call) {
			calls = append(calls, p.cd.call)
		}
	}
	sort.Slice(res.Stations, func(i, j int) bool { return res.Stations[i].Miles < res.Stations[j].Miles })
	sort.Strings(calls)
	res.Include = `\b(` + strings.Join(calls, "|") + `)\b`
	return res, nil
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}
