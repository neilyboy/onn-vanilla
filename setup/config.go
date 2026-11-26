package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// Setup is everything the wizard collects. It is stored only on this computer.
type Setup struct {
	LineupPrefix    string         `json:"lineupPrefix,omitempty"`
	LineupLocals    string         `json:"lineupLocals,omitempty"`
	LineupCats      []string       `json:"lineupCats,omitempty"`
	LineupExtra     []string       `json:"lineupExtra,omitempty"`
	LineupAdd       []string       `json:"lineupAdd,omitempty"`
	LineupHideEmpty bool           `json:"lineupHideEmpty,omitempty"`
	Server          string         `json:"server"`
	M3U             string         `json:"m3u"`
	EPG             string         `json:"epg"`
	Location        string         `json:"location"`
	Latitude        *float64       `json:"latitude"`
	Longitude       *float64       `json:"longitude"`
	Timezone        string         `json:"timezone"`
	Fahrenheit      bool           `json:"fahrenheit"`
	Theme           string         `json:"theme"`
	Accent          string         `json:"accent"`
	Clock24         bool           `json:"clock24"`
	ShowForecast    bool           `json:"showForecast"`
	Greeting        string         `json:"greeting"`
	Calendars       string         `json:"calendars,omitempty"`
	NewsWorld       bool           `json:"newsWorld,omitempty"`
	NewsLocal       string         `json:"newsLocal,omitempty"`
	NoAlerts        bool           `json:"noAlerts,omitempty"`
	IdleMinutes     int            `json:"idleMinutes"`
	Scale           float64        `json:"scale"`
	IconStyle       string         `json:"iconStyle,omitempty"`
	Layout          string         `json:"layout,omitempty"`
	WOnnow          string         `json:"wOnnow,omitempty"`
	WScores         string         `json:"wScores,omitempty"`
	WResume         string         `json:"wResume,omitempty"`
	WNewvod         string         `json:"wNewvod,omitempty"`
	WCalendar       string         `json:"wCalendar,omitempty"`
	WNews           string         `json:"wNews,omitempty"`
	WWeather        string         `json:"wWeather,omitempty"`
	Countdowns      string         `json:"countdowns,omitempty"`
	WHa             string         `json:"wHa,omitempty"`
	WCameras        string         `json:"wCameras,omitempty"`
	WRadio          string         `json:"wRadio,omitempty"`
	WRooms          string         `json:"wRooms,omitempty"`
	RadioStations   string         `json:"radioStations,omitempty"`
	HaURL           string         `json:"haUrl,omitempty"`
	HaToken         string         `json:"haToken,omitempty"`
	HaEntities      string         `json:"haEntities,omitempty"`
	Photos          string         `json:"photos,omitempty"`
	Profiles        []Profile      `json:"profiles"`
	AskProfile      bool           `json:"askProfile"`
	Leagues         []string       `json:"leagues"`
	Teams           []string       `json:"teams"`
	TeamInfo        map[string]any `json:"teamInfo,omitempty"`
	RemoteConfigURL string         `json:"remoteConfigUrl"`
	Boxes           []string       `json:"boxes"`
	XcUser          string         `json:"xcUser,omitempty"`
	XcPass          string         `json:"xcPass,omitempty"`
	Sources         []Source       `json:"sources,omitempty"`
}

// Source is an extra IPTV service (Xtream Codes login or M3U link) Live TV loads next to Dispatcharr.
type Source struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	URL      string `json:"url"`
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
	EPG      string `json:"epg,omitempty"`
}

// Profile is a Live TV "who's watching" entry: its groups and guide keywords are shown first.
type Profile struct {
	Name     string   `json:"name"`
	Sports   bool     `json:"sports"`
	Groups   []string `json:"groups"`
	Keywords []string `json:"keywords"`
	Color    string   `json:"color,omitempty"`
	Kids     bool     `json:"kids,omitempty"`
	PIN      string   `json:"pin,omitempty"`
}

func defaultProfiles() []Profile {
	return []Profile{
		{"Everyone", true, []string{}, []string{}, "", false, ""},
		{"Sports", true, []string{"Sports", "NFL", "College Football", "NBA", "NHL", "MLB", "Soccer", "Fights & PPV"}, []string{}, "", false, ""},
		{"Movies", false, []string{"Movies", "24/7 Channels"}, []string{"Western", "Comedy", "Action", "Thriller"}, "", false, ""},
		{"TV & Game Shows", false, []string{"Entertainment", "Free TV"}, []string{"Game Show", "Jeopardy", "Wheel of Fortune", "Family Feud", "Price Is Right"}, "", false, ""},
		{"News", false, []string{"News", "Locals"}, []string{}, "", false, ""},
		{"Kids", false, []string{"Kids"}, []string{}, "", true, ""},
	}
}

func defaultSetup() Setup {
	return Setup{
		Fahrenheit:   true,
		Theme:        "midnight",
		ShowForecast: true,
		IdleMinutes:  5,
		Scale:        1,
		Profiles:     defaultProfiles(),
		Leagues:      []string{"nfl", "college-football", "nba", "nhl"},
	}
}

func setupPath() string { return filepath.Join(dataDir, "setup.json") }

func loadSetup() Setup {
	s := defaultSetup()
	if b, err := os.ReadFile(setupPath()); err == nil {
		_ = json.Unmarshal(b, &s)
	}
	return s
}

func saveSetup(s Setup) error {
	b, _ := json.MarshalIndent(s, "", "  ")
	return os.WriteFile(setupPath(), b, 0o600)
}

func (s Setup) playlistURLs() (string, string, error) {
	m3u, epg := strings.TrimSpace(s.M3U), strings.TrimSpace(s.EPG)
	base := strings.TrimRight(strings.TrimSpace(s.Server), "/")
	if base != "" && !strings.Contains(base, "://") {
		base = "http://" + base
	}
	if m3u == "" && base != "" {
		m3u = base + "/output/m3u"
	}
	if epg == "" && base != "" {
		epg = base + "/output/epg"
	}
	if m3u == "" {
		return "", "", errors.New("enter your Dispatcharr address (or a playlist URL) first")
	}
	return m3u, epg, nil
}

// boxConfig builds the JSON both TV apps read (same format as server/launcher-config.example.json).
func (s Setup) boxConfig() (map[string]any, error) {
	m3u, epg, err := s.playlistURLs()
	if err != nil {
		return nil, err
	}
	unit := "celsius"
	if s.Fahrenheit {
		unit = "fahrenheit"
	}
	idle := s.IdleMinutes * 60
	if idle <= 0 {
		idle = 0
	}
	leagues := s.Leagues
	if leagues == nil {
		leagues = []string{}
	}
	teams := s.Teams
	if teams == nil {
		teams = []string{}
	}
	scale := s.Scale
	if scale < 0.6 || scale > 1.6 {
		scale = 1
	}
	var profiles []Profile
	for _, p := range s.Profiles {
		if strings.TrimSpace(p.Name) != "" {
			profiles = append(profiles, p)
		}
	}
	if len(profiles) == 0 {
		profiles = defaultProfiles()
	}
	return map[string]any{
		"version": 1,
		"theme": map[string]any{
			"name": s.Theme, "accent": s.Accent, "wallpaper_mode": "gradient", "clock_24h": s.Clock24,
		},
		"ui": map[string]any{
			"wallpaper_interval_sec": 45, "idle_timeout_sec": idle, "ken_burns": true,
			"show_weather": s.Latitude != nil, "show_forecast": s.ShowForecast, "show_settings": true,
			"greeting": s.Greeting, "scale": scale, "icon_style": s.IconStyle,
		},
		"widgets": widgets(s),
		"ha":      map[string]any{"url": strings.TrimSpace(s.HaURL), "token": strings.TrimSpace(s.HaToken), "entities": entityIDs(s.HaEntities)},
		"photos":  map[string]any{"sources": lines(s.Photos)},
		"radio":   map[string]any{"stations": lines(s.RadioStations)},
		"feeds": map[string]any{
			"calendars": lines(s.Calendars), "news": newsFeeds(s), "alerts": !s.NoAlerts,
		},
		"weather": map[string]any{
			"latitude": s.Latitude, "longitude": s.Longitude, "unit": unit, "location_name": s.Location,
		},
		"apps": []any{
			map[string]any{"id": "livetv", "label": "Live TV", "package": "tv.vanilla.live", "icon": "livetv"},
		},
		"livetv": map[string]any{
			"m3u_url": m3u, "epg_url": epg, "leagues": leagues, "favorite_teams": teams,
			"accent":      firstNonEmpty(s.Accent, "#E5484D"),
			"profiles":    profiles,
			"ask_profile": s.AskProfile,
			"xc":          xcConfig(s),
			"sources":     s.Sources,
			"network_aliases": map[string][]string{
				"Prime Video": {"Prime Video NFL", "Amazon Prime"},
				"NFL Net":     {"NFL Network"},
				"ESPN+":       {"ESPN Plus"},
				"Peacock":     {"Peacock NFL", "Peacock Premium"},
			},
		},
	}, nil
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}

// xcConfig is the Dispatcharr Xtream login Live TV uses for Movies & Series (nil when not enabled).
func xcConfig(s Setup) any {
	if s.XcUser == "" || s.XcPass == "" {
		return nil
	}
	return map[string]any{"username": s.XcUser, "password": s.XcPass}
}

// WorldNewsFeeds are used when the user ticks "World headlines" in the wizard.
var WorldNewsFeeds = []string{"https://feeds.bbci.co.uk/news/world/rss.xml", "https://feeds.npr.org/1004/rss.xml"}

// widgets lists the home screen widgets in display order with their spot (left, center or right).
func widgets(s Setup) map[string]any {
	layout := s.Layout
	if layout == "" {
		layout = "minimal"
	}
	items := []map[string]string{}
	for _, w := range []struct{ id, pos string }{
		{"weather", s.WWeather}, {"calendar", s.WCalendar}, {"news", s.WNews},
		{"ha", s.WHa}, {"scores", s.WScores}, {"onnow", s.WOnnow}, {"cameras", s.WCameras}, {"resume", s.WResume}, {"newvod", s.WNewvod}, {"radio", s.WRadio}, {"rooms", s.WRooms},
	} {
		if w.pos == "left" || w.pos == "center" || w.pos == "right" {
			items = append(items, map[string]string{"id": w.id, "pos": w.pos})
		}
	}
	return map[string]any{"layout": layout, "items": items, "countdowns": lines(s.Countdowns)}
}

func newsFeeds(s Setup) []string {
	f := lines(s.NewsLocal)
	if s.NewsWorld {
		f = append(f, WorldNewsFeeds...)
	}
	return f
}

// lines splits a textarea value into its non-empty trimmed lines.
// entityIDs accepts Home Assistant entity IDs separated by commas, spaces or new lines.
func entityIDs(v string) []string {
	return strings.FieldsFunc(v, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == '\t' || r == '\r' })
}

func lines(v string) []string {
	out := []string{}
	for _, l := range strings.Split(v, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}
