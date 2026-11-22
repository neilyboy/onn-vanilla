package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Dispatcharr lineup builder: merges the same channel from every provider into one channel
// with backups, ordered by each provider's stream limit. Talks only to Dispatcharr's REST API.

type dcClient struct{ base, token string }

func dcLogin(base, user, pass string) (*dcClient, error) {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	if base == "" {
		return nil, errors.New("enter your Dispatcharr address first")
	}
	c := &dcClient{base: base}
	var out struct {
		Access string `json:"access"`
	}
	if err := c.do("POST", "/api/accounts/token/", map[string]string{"username": user, "password": pass}, &out); err != nil {
		return nil, fmt.Errorf("Dispatcharr login failed: %v", err)
	}
	c.token = out.Access
	return c, nil
}

func (c *dcClient) do(method, path string, body, out any) error {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	cl := &http.Client{Timeout: 120 * time.Second}
	resp, err := cl.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		msg := string(data)
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return fmt.Errorf("%s %s: %s %s", method, path, resp.Status, msg)
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

type dcAccount struct {
	ID         int    `json:"id"`
	Name       string `json:"name"`
	Type       string `json:"account_type"`
	MaxStreams int    `json:"max_streams"`
	Active     bool   `json:"is_active"`
	ServerURL  string `json:"server_url"`
	Username   string `json:"username"`
	Password   string `json:"password"`
	Priority   int    `json:"priority"`
}

type dcGroup struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	Accounts []struct {
		StreamCount int `json:"stream_count"`
	} `json:"m3u_accounts"`
}

func (g dcGroup) streams() int {
	n := 0
	for _, a := range g.Accounts {
		n += a.StreamCount
	}
	return n
}

type dcStream struct {
	ID      int    `json:"id"`
	Name    string `json:"name"`
	Account int    `json:"m3u_account"`
	Logo    string `json:"logo_url"`
	TvgID   string `json:"tvg_id"`
	Group   int    `json:"channel_group"`
	Stale   bool   `json:"is_stale"`
}

func (c *dcClient) accounts() ([]dcAccount, error) {
	var a []dcAccount
	return a, c.do("GET", "/api/m3u/accounts/", nil, &a)
}

func (c *dcClient) groups() ([]dcGroup, error) {
	var g []dcGroup
	return g, c.do("GET", "/api/channels/groups/", nil, &g)
}

// LimitInfo reports what each provider allows vs. what Dispatcharr is set to.
type LimitInfo struct {
	Name     string `json:"name"`
	Current  int    `json:"current"`
	Provider int    `json:"provider"`
	Updated  bool   `json:"updated"`
}

func (c *dcClient) syncLimits(apply bool) ([]LimitInfo, error) {
	accts, err := c.accounts()
	if err != nil {
		return nil, err
	}
	var out []LimitInfo
	for _, a := range accts {
		if a.Type != "XC" || !a.Active || a.Username == "" {
			continue
		}
		li := LimitInfo{Name: a.Name, Current: a.MaxStreams}
		var r struct {
			UserInfo struct {
				Max json.Number `json:"max_connections"`
			} `json:"user_info"`
		}
		u := strings.TrimRight(a.ServerURL, "/") + "/player_api.php?username=" + url.QueryEscape(a.Username) + "&password=" + url.QueryEscape(a.Password)
		if getJSON(u, &r) == nil {
			if n, err := r.UserInfo.Max.Int64(); err == nil && n > 0 {
				li.Provider = int(n)
				if apply && int(n) != a.MaxStreams {
					if c.do("PATCH", fmt.Sprintf("/api/m3u/accounts/%d/", a.ID), map[string]int{"max_streams": int(n)}, nil) == nil {
						li.Updated, li.Current = true, int(n)
					}
				}
			}
		}
		out = append(out, li)
	}
	return out, nil
}

// LineupGroup is one group of the merged lineup, e.g. "Sports" built from every provider's US sports groups.
type LineupGroup struct {
	Name    string   `json:"name"`
	Start   int      `json:"start"`
	Sources []string `json:"sources"`           // regexes on provider group names
	Include string   `json:"include,omitempty"` // regex on stream names (e.g. your city / call letters)
	Exclude string   `json:"exclude,omitempty"`
	Skip    string   `json:"skip_groups,omitempty"` // provider groups to leave out
	NoMerge bool     `json:"-"`                     // event slots: one channel per stream
}

type plannedChannel struct {
	Name    string `json:"name"`
	TvgID   string `json:"tvgId"`
	Streams []int  `json:"-"`
	Count   int    `json:"streams"`
}

type plannedGroup struct {
	Name     string           `json:"name"`
	Start    int              `json:"start"`
	Sources  []string         `json:"sources"`
	Channels []plannedChannel `json:"channels"`
}

var (
	reJunk   = regexp.MustCompile(`(?i)\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|alt|alternative)\b|\(.*?\)|\[.*?\]`)
	rePrefix = regexp.MustCompile(`(?i)^\s*(us|usa|united states|uk|ca|au)\s*[:|\-]?\s+`)
	reNonAZ  = regexp.MustCompile(`[^a-z0-9]+`)
)

func nameKey(n string) string {
	n = reJunk.ReplaceAllString(rePrefix.ReplaceAllString(n, ""), " ")
	return reNonAZ.ReplaceAllString(strings.ToLower(n), "")
}

func tvgKey(t string) string {
	t = strings.ToLower(strings.TrimSpace(t))
	if t == "" || strings.Contains(t, "dummy") || t == "none" || t == "null" {
		return ""
	}
	return t
}

func (c *dcClient) streamsIn(groups []dcGroup) ([]dcStream, error) {
	ids := map[int]bool{}
	var names []string
	for _, g := range groups {
		ids[g.ID] = true
		names = append(names, g.Name)
	}
	var all []dcStream
	// channel_group_name is an OR'd "contains" filter; fetch in small batches and keep exact group matches
	for i := 0; i < len(names); i += 8 {
		j := min(i+8, len(names))
		next := "/api/channels/streams/?page_size=10000&channel_group_name=" + url.QueryEscape(strings.Join(names[i:j], ","))
		for next != "" {
			var page struct {
				Next    *string    `json:"next"`
				Results []dcStream `json:"results"`
			}
			if err := c.do("GET", next, nil, &page); err != nil {
				return nil, err
			}
			for _, s := range page.Results {
				if ids[s.Group] && !s.Stale {
					all = append(all, s)
				}
			}
			next = ""
			if page.Next != nil {
				if u, err := url.Parse(*page.Next); err == nil {
					next = u.RequestURI()
				}
			}
		}
	}
	seen := map[int]bool{}
	out := all[:0]
	for _, s := range all {
		if !seen[s.ID] {
			seen[s.ID] = true
			out = append(out, s)
		}
	}
	return out, nil
}

func (c *dcClient) plan(specs []LineupGroup) ([]plannedGroup, map[int]string, error) {
	accts, err := c.accounts()
	if err != nil {
		return nil, nil, err
	}
	sort.SliceStable(accts, func(i, j int) bool {
		if accts[i].MaxStreams != accts[j].MaxStreams {
			return accts[i].MaxStreams > accts[j].MaxStreams
		}
		return accts[i].Priority > accts[j].Priority
	})
	rank, active := map[int]int{}, map[int]bool{}
	for i, a := range accts {
		rank[a.ID] = i
		active[a.ID] = a.Active
	}
	groups, err := c.groups()
	if err != nil {
		return nil, nil, err
	}
	logos := map[int]string{}
	claimed := map[int]bool{}
	own := map[string]bool{}
	for _, spec := range specs {
		own[spec.Name] = true
	}
	globalSkip := regexp.MustCompile(`(?i)low ?bw|\btest\b|^temp`)
	var out []plannedGroup
	for _, spec := range specs {
		skip, _ := regexp.Compile("(?i)" + spec.Skip)
		var pats []*regexp.Regexp
		for _, p := range spec.Sources {
			if re, err := regexp.Compile("(?i)" + p); err == nil {
				pats = append(pats, re)
			}
		}
		var src []dcGroup
		for _, g := range groups {
			if claimed[g.ID] || own[g.Name] || globalSkip.MatchString(g.Name) || (spec.Skip != "" && skip != nil && skip.MatchString(g.Name)) {
				continue
			}
			for _, p := range pats {
				if p.MatchString(g.Name) {
					src = append(src, g)
					claimed[g.ID] = true
					break
				}
			}
		}
		streams, err := c.streamsIn(src)
		if err != nil {
			return nil, nil, err
		}
		inc, _ := regexp.Compile("(?i)" + spec.Include)
		exc, _ := regexp.Compile("(?i)" + spec.Exclude)
		var kept []dcStream
		for _, s := range streams {
			if !active[s.Account] || (spec.Include != "" && inc != nil && !inc.MatchString(s.Name)) || (spec.Exclude != "" && exc != nil && exc.MatchString(s.Name)) {
				continue
			}
			kept = append(kept, s)
		}
		parent := make([]int, len(kept))
		for i := range parent {
			parent[i] = i
		}
		var find func(int) int
		find = func(i int) int {
			for parent[i] != i {
				parent[i] = parent[parent[i]]
				i = parent[i]
			}
			return i
		}
		seen := map[string]int{}
		for i, s := range kept {
			if spec.NoMerge {
				break
			}
			keys := []string{"n:" + nameKey(s.Name)}
			if t := tvgKey(s.TvgID); t != "" {
				keys = append(keys, "t:"+t)
			}
			for _, k := range keys {
				if k == "n:" {
					continue
				}
				if j, ok := seen[k]; ok {
					parent[find(i)] = find(j)
				} else {
					seen[k] = i
				}
			}
		}
		clusters := map[int][]dcStream{}
		for i, s := range kept {
			r := find(i)
			clusters[r] = append(clusters[r], s)
		}
		pg := plannedGroup{Name: spec.Name, Start: spec.Start}
		for _, g := range src {
			pg.Sources = append(pg.Sources, g.Name)
		}
		for _, m := range clusters {
			sort.SliceStable(m, func(i, j int) bool {
				if rank[m[i].Account] != rank[m[j].Account] {
					return rank[m[i].Account] < rank[m[j].Account]
				}
				return m[i].ID < m[j].ID
			})
			name, tvg := "", ""
			for _, s := range m {
				n := strings.TrimSpace(rePrefix.ReplaceAllString(s.Name, ""))
				if name == "" || len(n) < len(name) || (len(n) == len(name) && n < name) {
					name = n
				}
				if tvg == "" && tvgKey(s.TvgID) != "" {
					tvg = s.TvgID
				}
			}
			ch := plannedChannel{Name: name, TvgID: tvg, Count: len(m)}
			for _, s := range m {
				ch.Streams = append(ch.Streams, s.ID)
				if logos[m[0].ID] == "" && s.Logo != "" {
					logos[m[0].ID] = s.Logo
				}
			}
			pg.Channels = append(pg.Channels, ch)
		}
		sort.Slice(pg.Channels, func(i, j int) bool {
			return strings.ToLower(pg.Channels[i].Name) < strings.ToLower(pg.Channels[j].Name)
		})
		out = append(out, pg)
	}
	return out, logos, nil
}

func (c *dcClient) apply(plan []plannedGroup, log logger) error {
	groups, err := c.groups()
	if err != nil {
		return err
	}
	byName := map[string]int{}
	for _, g := range groups {
		byName[g.Name] = g.ID
	}
	var channels []struct {
		ID    int `json:"id"`
		Group int `json:"channel_group_id"`
	}
	var page struct {
		Results json.RawMessage `json:"results"`
	}
	if err := c.do("GET", "/api/channels/channels/?page_size=100000", nil, &page); err != nil {
		return err
	}
	_ = json.Unmarshal(page.Results, &channels)
	total := 0
	for _, pg := range plan {
		gid, ok := byName[pg.Name]
		if !ok {
			var g dcGroup
			if err := c.do("POST", "/api/channels/groups/", map[string]string{"name": pg.Name}, &g); err != nil {
				return err
			}
			gid = g.ID
		}
		var old []int
		for _, ch := range channels {
			if ch.Group == gid {
				old = append(old, ch.ID)
			}
		}
		if len(old) > 0 {
			log("  %s: replacing %d existing channels", pg.Name, len(old))
			if err := c.do("DELETE", "/api/channels/channels/bulk-delete/", map[string]any{"channel_ids": old}, nil); err != nil {
				return err
			}
		}
		for i, ch := range pg.Channels {
			num := pg.Start + i
			var made struct {
				ID int `json:"id"`
			}
			if err := c.do("POST", "/api/channels/channels/from-stream/", map[string]any{"stream_id": ch.Streams[0], "name": ch.Name, "channel_number": num}, &made); err != nil {
				log("  ! %s: %v", ch.Name, err)
				continue
			}
			patch := map[string]any{"channel_group_id": gid, "streams": ch.Streams, "channel_number": num, "name": ch.Name}
			if ch.TvgID != "" {
				patch["tvg_id"] = ch.TvgID
			}
			if err := c.do("PATCH", fmt.Sprintf("/api/channels/channels/%d/", made.ID), patch, nil); err != nil {
				log("  ! %s: %v", ch.Name, err)
			}
			total++
		}
		log("  ✓ %s: %d channels", pg.Name, len(pg.Channels))
	}
	log("Matching channels to the guide…")
	_ = c.do("POST", "/api/channels/channels/match-epg/", map[string]any{}, nil)
	log("Done: %d channels. Guide data fills in over the next few minutes.", total)
	return nil
}

//go:embed lineup-preset.json
var lineupPreset []byte

type presetGroup struct {
	LineupGroup
	Merge *bool `json:"merge"`
}

func presetGroups() []LineupGroup {
	var f struct{ Groups []presetGroup }
	_ = json.Unmarshal(lineupPreset, &f)
	out := make([]LineupGroup, 0, len(f.Groups))
	for _, g := range f.Groups {
		g.LineupGroup.NoMerge = g.Merge != nil && !*g.Merge
		out = append(out, g.LineupGroup)
	}
	return out
}

// presetLineup returns the default lineup (everything except foreign groups) for a country
// prefix like "US|USA", limited to the chosen group names, with an optional local market.
func presetLineup(prefix string, cats []string, locals string) []LineupGroup {
	want := map[string]bool{}
	for _, c := range cats {
		want[strings.ToLower(c)] = true
	}
	locals = strings.TrimSpace(locals)
	var out []LineupGroup
	for _, g := range presetGroups() {
		if len(want) > 0 && !want[strings.ToLower(g.Name)] {
			continue
		}
		if g.Include == "YOUR_CITY|YOUR_CALL_LETTERS" {
			if locals == "" {
				continue
			}
			g.Include = locals
		}
		if prefix != "" && prefix != "US|USA" {
			for i, src := range g.Sources {
				g.Sources[i] = strings.ReplaceAll(src, "(US|USA)", "("+prefix+")")
			}
		}
		out = append(out, g)
	}
	return out
}
