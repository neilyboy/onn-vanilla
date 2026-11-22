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
	IdleMinutes     int            `json:"idleMinutes"`
	Scale           float64        `json:"scale"`
	Profiles        []Profile      `json:"profiles"`
	AskProfile      bool           `json:"askProfile"`
	Leagues         []string       `json:"leagues"`
	Teams           []string       `json:"teams"`
	TeamInfo        map[string]any `json:"teamInfo,omitempty"`
	RemoteConfigURL string         `json:"remoteConfigUrl"`
	Boxes           []string       `json:"boxes"`
	XcUser          string         `json:"xcUser,omitempty"`
	XcPass          string         `json:"xcPass,omitempty"`
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
			"greeting": s.Greeting, "scale": scale,
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
