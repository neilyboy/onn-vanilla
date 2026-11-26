package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var httpc = &http.Client{Timeout: 30 * time.Second}

func registerAPI(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/setup", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, loadSetup()) })
	mux.HandleFunc("POST /api/setup", func(w http.ResponseWriter, r *http.Request) {
		var s Setup
		if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
			writeErr(w, err)
			return
		}
		if err := saveSetup(s); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]any{"ok": true, "path": setupPath()})
	})
	mux.HandleFunc("POST /api/preview", func(w http.ResponseWriter, r *http.Request) {
		var s Setup
		if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
			writeErr(w, err)
			return
		}
		cfg, err := s.boxConfig()
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, cfg)
	})
	mux.HandleFunc("POST /api/test-playlist", handleTestPlaylist)
	mux.HandleFunc("GET /api/geocode", handleGeocode)
	mux.HandleFunc("GET /api/localnews", handleLocalNews)
	mux.HandleFunc("GET /api/teams", handleTeams)
	mux.HandleFunc("GET /api/bloat", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, bloatList()) })
	mux.HandleFunc("GET /api/adb", func(w http.ResponseWriter, r *http.Request) {
		p := findADB()
		v := ""
		if p != "" {
			out, _ := ADB{path: p}.run(10*time.Second, "version")
			v, _, _ = strings.Cut(out, "\n")
		}
		_, _, apkErr := apkFolder()
		writeJSON(w, map[string]any{"path": p, "version": v, "apks": apkErr == nil, "dataDir": dataDir})
	})
	type lineupReq struct {
		User, Pass string
		LineupOpts
	}
	lineupClient := func(r *http.Request) (*dcClient, lineupReq, error) {
		var b lineupReq
		_ = json.NewDecoder(r.Body).Decode(&b)
		c, err := dcLogin(loadSetup().Server, b.User, b.Pass)
		return c, b, err
	}
	mux.HandleFunc("POST /api/vod/enable", func(w http.ResponseWriter, r *http.Request) {
		c, _, err := lineupClient(r)
		if err != nil {
			writeErr(w, err)
			return
		}
		pass, err := c.enableXC("vanilla-tv")
		if err != nil {
			writeErr(w, err)
			return
		}
		s := loadSetup()
		s.XcUser, s.XcPass = "vanilla-tv", pass
		if err := saveSetup(s); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]any{"ok": true, "user": s.XcUser})
	})
	mux.HandleFunc("POST /api/box/pair", func(w http.ResponseWriter, r *http.Request) {
		var b struct{ Pair, Code string }
		_ = json.NewDecoder(r.Body).Decode(&b)
		out, err := pair(b.Pair, b.Code)
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]any{"ok": true, "message": out})
	})
	mux.HandleFunc("POST /api/box/inspect", func(w http.ResponseWriter, r *http.Request) {
		var o ProvisionOpts
		_ = json.NewDecoder(r.Body).Decode(&o)
		var notes []string
		a, err := connect(o.IP, func(m string) { notes = append(notes, m) })
		if err != nil {
			writeErr(w, err)
			return
		}
		info, err := inspect(a)
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, info)
	})
	mux.HandleFunc("POST /api/box/provision", func(w http.ResponseWriter, r *http.Request) {
		var o ProvisionOpts
		if err := json.NewDecoder(r.Body).Decode(&o); err != nil {
			writeErr(w, err)
			return
		}
		stream(w, func(log logger) error { return provision(o, loadSetup(), log) })
	})
	mux.HandleFunc("POST /api/box/restore", func(w http.ResponseWriter, r *http.Request) {
		var o ProvisionOpts
		_ = json.NewDecoder(r.Body).Decode(&o)
		stream(w, func(log logger) error { return restore(o.IP, log) })
	})
}

// stream sends log lines as they happen (plain text, chunked).
func stream(w http.ResponseWriter, fn func(logger) error) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	fl, _ := w.(http.Flusher)
	log := func(format string, args ...any) {
		fmt.Fprintf(w, format+"\n", args...)
		if fl != nil {
			fl.Flush()
		}
	}
	if err := fn(log); err != nil {
		log("ERROR: %v", err)
	}
}

var (
	reGroup = regexp.MustCompile(`group-title="([^"]*)"`)
	reEpgCh = regexp.MustCompile(`<channel `)
)

func handleTestPlaylist(w http.ResponseWriter, r *http.Request) {
	var s Setup
	if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
		writeErr(w, err)
		return
	}
	m3u, epg, err := s.playlistURLs()
	if err != nil {
		writeErr(w, err)
		return
	}
	res := map[string]any{"m3u": m3u, "epg": epg}
	resp, err := httpc.Get(m3u)
	if err != nil {
		writeErr(w, fmt.Errorf("can't reach %s: %v", m3u, err))
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		writeErr(w, fmt.Errorf("%s returned %s", m3u, resp.Status))
		return
	}
	channels, groups, sample := 0, map[string]int{}, []string{}
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	header := false
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(l, "#EXTM3U") {
			header = true
		}
		if strings.HasPrefix(l, "#EXTINF") {
			channels++
			if m := reGroup.FindStringSubmatch(l); m != nil {
				groups[m[1]]++
			}
			if i := strings.LastIndex(l, ","); i >= 0 && len(sample) < 8 {
				sample = append(sample, strings.TrimSpace(l[i+1:]))
			}
		}
	}
	if !header {
		writeErr(w, fmt.Errorf("%s isn't an M3U playlist", m3u))
		return
	}
	res["channels"], res["groups"], res["sample"] = channels, len(groups), sample
	if epg != "" {
		if er, err := httpc.Get(epg); err != nil {
			res["epgError"] = err.Error()
		} else {
			b, _ := io.ReadAll(io.LimitReader(er.Body, 64<<20))
			er.Body.Close()
			res["epgChannels"] = len(reEpgCh.FindAll(b, -1))
			res["epgProgrammes"] = strings.Count(string(b), "<programme ")
		}
	}
	writeJSON(w, res)
}

func getJSON(u string, v any) error {
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", "onn-vanilla-setup")
	resp, err := httpc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s: %s", u, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}

var reZip = regexp.MustCompile(`^\d{5}$`)

// handleGeocode turns a US ZIP or a city name into coordinates + time zone (Zippopotam + Open-Meteo, no keys).
func handleGeocode(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	type place struct {
		Name     string  `json:"name"`
		Lat      float64 `json:"latitude"`
		Lon      float64 `json:"longitude"`
		Timezone string  `json:"timezone"`
	}
	var out []place
	if reZip.MatchString(q) {
		var z struct {
			Places []struct {
				Name  string `json:"place name"`
				Lat   string `json:"latitude"`
				Lon   string `json:"longitude"`
				State string `json:"state abbreviation"`
			} `json:"places"`
		}
		if err := getJSON("https://api.zippopotam.us/us/"+q, &z); err == nil {
			for _, p := range z.Places {
				lat, _ := strconv.ParseFloat(p.Lat, 64)
				lon, _ := strconv.ParseFloat(p.Lon, 64)
				out = append(out, place{Name: p.Name + ", " + p.State, Lat: lat, Lon: lon})
			}
		}
	} else if q != "" {
		var g struct {
			Results []struct {
				Name     string  `json:"name"`
				Admin1   string  `json:"admin1"`
				Country  string  `json:"country_code"`
				Lat      float64 `json:"latitude"`
				Lon      float64 `json:"longitude"`
				Timezone string  `json:"timezone"`
			} `json:"results"`
		}
		if err := getJSON("https://geocoding-api.open-meteo.com/v1/search?count=6&name="+url.QueryEscape(q), &g); err == nil {
			for _, p := range g.Results {
				n := p.Name
				if p.Admin1 != "" {
					n += ", " + p.Admin1
				}
				out = append(out, place{Name: n + " (" + p.Country + ")", Lat: p.Lat, Lon: p.Lon, Timezone: p.Timezone})
			}
		}
	}
	if len(out) == 0 {
		writeErr(w, errors.New("no match; try a city name or a 5-digit US ZIP"))
		return
	}
	for i := range out {
		if out[i].Timezone == "" {
			var f struct {
				Timezone string `json:"timezone"`
			}
			err := getJSON(fmt.Sprintf("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&current=temperature_2m", out[i].Lat, out[i].Lon), &f)
			if err == nil {
				out[i].Timezone = f.Timezone
			} else {
				log.Println("timezone lookup:", err)
			}
		}
	}
	writeJSON(w, out)
}

var leaguePaths = map[string]string{
	"nfl": "football/nfl", "college-football": "football/college-football",
	"nba": "basketball/nba", "mens-college-basketball": "basketball/mens-college-basketball",
	"wnba": "basketball/wnba", "nhl": "hockey/nhl", "mlb": "baseball/mlb",
	"mls": "soccer/usa.1", "epl": "soccer/eng.1",
}

func handleTeams(w http.ResponseWriter, r *http.Request) {
	p, ok := leaguePaths[r.URL.Query().Get("league")]
	if !ok {
		writeErr(w, errors.New("unknown league"))
		return
	}
	var d struct {
		Sports []struct {
			Leagues []struct {
				Teams []struct {
					Team struct {
						Abbr  string `json:"abbreviation"`
						Name  string `json:"displayName"`
						Short string `json:"shortDisplayName"`
						Color string `json:"color"`
						Logos []struct {
							Href string `json:"href"`
						} `json:"logos"`
					} `json:"team"`
				} `json:"teams"`
			} `json:"leagues"`
		} `json:"sports"`
	}
	if err := getJSON("https://site.api.espn.com/apis/site/v2/sports/"+p+"/teams?limit=1000", &d); err != nil {
		writeErr(w, err)
		return
	}
	type team struct {
		Abbr  string `json:"abbr"`
		Name  string `json:"name"`
		Color string `json:"color"`
		Logo  string `json:"logo"`
	}
	out := []team{}
	for _, s := range d.Sports {
		for _, l := range s.Leagues {
			for _, t := range l.Teams {
				logo := ""
				if len(t.Team.Logos) > 0 {
					logo = t.Team.Logos[0].Href
				}
				out = append(out, team{t.Team.Abbr, t.Team.Name, t.Team.Color, logo})
			}
		}
	}
	writeJSON(w, out)
}

// handleLocalNews suggests local headline feeds for a spot: Google News searches for the town and its county.
func handleLocalNews(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	town := strings.TrimSpace(strings.SplitN(q.Get("place"), ",", 2)[0])
	var rev struct {
		Address struct {
			County, City, Town, Village, State string
		} `json:"address"`
	}
	_ = getJSON(fmt.Sprintf("https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&addressdetails=1&lat=%s&lon=%s",
		url.QueryEscape(q.Get("lat")), url.QueryEscape(q.Get("lon"))), &rev)
	a := rev.Address
	if town == "" {
		town = firstNonEmpty(a.City, a.Town, a.Village)
	}
	gnews := func(s string) string {
		return "https://news.google.com/rss/search?q=" + url.QueryEscape(s+" when:3d") + "&hl=en-US&gl=US&ceid=US:en"
	}
	var feeds, areas []string
	for _, p := range []string{town, a.County} {
		if p == "" || contains(areas, p) {
			continue
		}
		areas = append(areas, p)
		if a.State != "" {
			p += ", " + a.State
		}
		feeds = append(feeds, gnews(p))
	}
	if len(feeds) == 0 {
		writeErr(w, errors.New("couldn't work out the area for local news"))
		return
	}
	writeJSON(w, map[string]any{"feeds": feeds, "areas": areas})
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}
