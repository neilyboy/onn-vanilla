package main

import (
	"bytes"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
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

// enableXC creates (or updates) a limited Dispatcharr user whose Xtream login the TV apps use for Movies & Series.
func (c *dcClient) enableXC(name string) (string, error) {
	var users []struct {
		ID       int    `json:"id"`
		Username string `json:"username"`
	}
	if err := c.do("GET", "/api/accounts/users/", nil, &users); err != nil {
		return "", err
	}
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	pass := hex.EncodeToString(b)
	body := map[string]any{"username": name, "user_level": 1, "custom_properties": map[string]any{"xc_password": pass}}
	for _, u := range users {
		if u.Username == name {
			return pass, c.do("PATCH", fmt.Sprintf("/api/accounts/users/%d/", u.ID), body, nil)
		}
	}
	body["password"] = hex.EncodeToString(b[:6]) + "Aa1!"
	return pass, c.do("POST", "/api/accounts/users/", body, nil)
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
	Name       string   `json:"name"`
	Start      int      `json:"start"`
	Merge      *bool    `json:"merge,omitempty"`   // false for event slots: one channel per stream
	Sources    []string `json:"sources"`           // regexes on provider group names
	Include    string   `json:"include,omitempty"` // regex on stream names (e.g. your city / call letters)
	Exclude    string   `json:"exclude,omitempty"`
	Skip       string   `json:"skip_groups,omitempty"` // provider groups to leave out
	AnyCountry bool     `json:"any_country,omitempty"` // keep streams even if they're from another country
}

func (g LineupGroup) noMerge() bool { return g.Merge != nil && !*g.Merge }

// Lineup is the whole channel list; it's also saved as lineup.json for the nightly refresh-lineup.sh.
type Lineup struct {
	Countries       string        `json:"countries,omitempty"` // country codes to keep, e.g. "US|USA|UK|GB"
	DropEmptyEvents bool          `json:"drop_empty_events,omitempty"`
	SkipGroups      string        `json:"skip_groups,omitempty"`
	Groups          []LineupGroup `json:"groups"`
}

var (
	// "DE: RTL" on streams, "AR | beIN" on groups. Streams only count "XX:" so US state locals
	// like "IL | Springfield | CBS" aren't mistaken for Israel.
	reCode       = regexp.MustCompile(`^\s*([A-Z]{2,4})\s*:`)
	reGroupCode  = regexp.MustCompile(`^\s*([A-Z]{2,4})\s*[:|]`)
	reEmptyEvent = regexp.MustCompile(`(?i)no events?\b|no games?\b|off ?air|^[^:]*:\s*$|\(209\d`)
	countryCodes = map[string]bool{}
)

func init() {
	for _, c := range strings.Fields(`AD AE AF AL AM AO AR AT AU AZ BA BD BE BG BH BO BR BY CA CH CL CN CO CR CU CY CZ DE DK DO DZ
EC EE EG ES ET FI FR GB GE GH GR GT HK HN HR HT HU ID IE IL IN IQ IR IS IT JM JO JP KE KR KW KZ LB LK LT LU LV LY MA MK
MT MX MY NG NI NL NO NP NZ OM PA PE PH PK PL PR PS PT PY QA RO RS RU SA SE SG SI SK SN SV SY TH TN TR TW TZ UA UG UK US
UY UZ VE VN YE ZA USA GER FRA ESP ITA POR POL NED SWE NOR DEN FIN TUR ARA RUS BRA ARG MEX IND PAK EXYU AFR CAN AUS`) {
		countryCodes[c] = true
	}
}

// foreign reports whether a stream (or provider group) name starts with another country's code.
func (l Lineup) foreign(name string, group bool) bool {
	if l.Countries == "" {
		return false
	}
	re := reCode
	if group {
		re = reGroupCode
	}
	m := re.FindStringSubmatch(name)
	if m == nil || !countryCodes[m[1]] {
		return false
	}
	for _, c := range strings.Split(l.Countries, "|") {
		if strings.EqualFold(strings.TrimSpace(c), m[1]) {
			return false
		}
	}
	return true
}

func (l Lineup) skipRe() *regexp.Regexp {
	if re, err := regexp.Compile("(?i)" + l.SkipGroups); err == nil && l.SkipGroups != "" {
		return re
	}
	return regexp.MustCompile(`(?i)low ?bw|\btest\b|^temp`)
}

// claim returns the provider groups each lineup group takes; earlier lineup groups win.
func (l Lineup) claim(groups []dcGroup) [][]dcGroup {
	own := map[string]bool{}
	for _, spec := range l.Groups {
		own[spec.Name] = true
	}
	claimed := map[int]bool{}
	globalSkip := l.skipRe()
	out := make([][]dcGroup, len(l.Groups))
	for i, spec := range l.Groups {
		skip, _ := regexp.Compile("(?i)" + spec.Skip)
		var pats []*regexp.Regexp
		for _, p := range spec.Sources {
			if re, err := regexp.Compile("(?i)" + p); err == nil {
				pats = append(pats, re)
			}
		}
		for _, g := range groups {
			if claimed[g.ID] || (own[g.Name] && g.Name != spec.Name) || globalSkip.MatchString(g.Name) || (spec.Skip != "" && skip != nil && skip.MatchString(g.Name)) ||
				(!spec.AnyCountry && l.foreign(g.Name, true)) {
				continue
			}
			for _, p := range pats {
				if p.MatchString(g.Name) {
					out[i] = append(out[i], g)
					claimed[g.ID] = true
					break
				}
			}
		}
	}
	return out
}

// HiddenGroup is a provider group the lineup leaves out.
type HiddenGroup struct {
	Name    string `json:"name"`
	Streams int    `json:"streams"`
	Reason  string `json:"reason"`
}

func (c *dcClient) hidden(l Lineup) ([]HiddenGroup, error) {
	groups, err := c.groups()
	if err != nil {
		return nil, err
	}
	taken := map[string]bool{}
	for _, spec := range l.Groups {
		taken[spec.Name] = true
	}
	for _, src := range l.claim(groups) {
		for _, g := range src {
			taken[g.Name] = true
		}
	}
	skip := l.skipRe()
	out := []HiddenGroup{}
	for _, g := range groups {
		if taken[g.Name] || g.streams() == 0 {
			continue
		}
		h := HiddenGroup{Name: g.Name, Streams: g.streams(), Reason: "not in any group"}
		if skip.MatchString(g.Name) {
			h.Reason = "test / low quality"
		} else if l.foreign(g.Name, true) {
			h.Reason = "other country"
		}
		out = append(out, h)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out, nil
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
	reJunk   = regexp.MustCompile(`(?i)\b(hd|fhd|uhd|4k|sd|hevc|h265|lb|hb|backup|alt|alternative)\b|\(.*?\)|\[.*?\]`)
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

func (c *dcClient) plan(l Lineup) ([]plannedGroup, map[int]string, error) {
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
	claims := l.claim(groups)
	var out []plannedGroup
	for gi, spec := range l.Groups {
		src := claims[gi]
		streams, err := c.streamsIn(src)
		if err != nil {
			return nil, nil, err
		}
		inc, _ := regexp.Compile("(?i)" + spec.Include)
		exc, _ := regexp.Compile("(?i)" + spec.Exclude)
		var kept []dcStream
		for _, s := range streams {
			if !active[s.Account] || (spec.Include != "" && inc != nil && !inc.MatchString(s.Name)) || (spec.Exclude != "" && exc != nil && exc.MatchString(s.Name)) ||
				(!spec.AnyCountry && l.foreign(s.Name, false)) || (l.DropEmptyEvents && spec.noMerge() && reEmptyEvent.MatchString(s.Name)) {
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
			if spec.noMerge() {
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

func preset() Lineup {
	var l Lineup
	_ = json.Unmarshal(lineupPreset, &l)
	return l
}

// LineupOpts are the wizard's channel-list choices.
type LineupOpts struct {
	Prefix, Locals string
	Cats           []string
	Extra          []string // more countries as their own groups, e.g. "UK|GB"
	Add            []string // provider groups from "What's left out" to add as they are
	HideEmpty      bool     // drop event slots named "NO EVENT" when the list is built
}

var countryLabels = map[string]string{"US|USA": "US", "UK|GB": "UK", "CA|CANADA": "Canada", "AU|AUSTRALIA": "Australia", "IE|IRELAND": "Ireland"}

func addedName(src string) string {
	parts := strings.Split(src, "|")
	if len(parts) == 1 {
		return strings.TrimSpace(src) + " (added)"
	}
	for i, j := 0, len(parts)-1; i < j; i, j = i+1, j-1 {
		parts[i], parts[j] = parts[j], parts[i]
	}
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return strings.Join(parts, " · ")
}

// presetLineup returns the default lineup (everything except foreign groups) for a country
// prefix like "US|USA", limited to the chosen group names, with an optional local market,
// extra countries and hand-picked provider groups.
func presetLineup(o LineupOpts) Lineup {
	prefix, locals := o.Prefix, strings.TrimSpace(o.Locals)
	if prefix == "" {
		prefix = "US|USA"
	}
	want := map[string]bool{}
	for _, c := range o.Cats {
		want[strings.ToLower(c)] = true
	}
	l := preset()
	l.DropEmptyEvents = o.HideEmpty
	countries := []string{prefix}
	var front, back, groups []LineupGroup
	for i, a := range o.Add {
		front = append(front, LineupGroup{Name: addedName(a), Start: 80000 + i*1000, Sources: []string{"^" + regexp.QuoteMeta(a) + "$"}, AnyCountry: true})
	}
	no := false
	n := 0
	for _, x := range o.Extra {
		if x == prefix || x == "" {
			continue
		}
		label := countryLabels[x]
		if label == "" {
			label = strings.SplitN(x, "|", 2)[0]
		}
		countries = append(countries, x)
		base, p := 50000+n*4000, "^("+x+")\\s*\\|"
		n++
		front = append(front,
			LineupGroup{Name: label + " Sports Events", Start: base + 3000, Merge: &no, Sources: []string{p + ".*(event|live only|ppv|max sports|pre season)"}},
			LineupGroup{Name: label + " Sports", Start: base + 2000, Sources: []string{p + ".*(sport|tnt|dazn|premier|tennis|league|ifollow|f1|racing|golf|cricket|rugby|football|bein|eurosport|tsn|kayo|stan)"}})
		back = append(back, LineupGroup{Name: label + " TV", Start: base, Sources: []string{p},
			Skip: "low|bandwidth|plus ?1|shopping|betting|religion|regional|asian|internationals|adult|xxx|replay|sport|tnt|dazn|event|ifollow|league|tennis"})
	}
	l.Countries = strings.Join(countries, "|")
	for _, g := range l.Groups {
		if len(want) > 0 && !want[strings.ToLower(g.Name)] {
			continue
		}
		if g.Include == "YOUR_CITY|YOUR_CALL_LETTERS" {
			if locals == "" {
				continue
			}
			g.Include = locals
		}
		if prefix != "US|USA" {
			src := append([]string(nil), g.Sources...)
			for i := range src {
				src[i] = strings.ReplaceAll(src[i], "(US|USA)", "("+prefix+")")
			}
			g.Sources = src
		}
		groups = append(groups, g)
	}
	l.Groups = append(append(front, groups...), back...)
	return l
}
