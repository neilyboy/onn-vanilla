package main

// Keeping boxes up to date: versions of the bundled apps, per-box status, app updates that leave
// each TV's settings alone, and a saved Home (look, layout, dock) that other TVs can be given.

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shogo82148/androidbinary/apk"
)

type appVersion struct {
	Code int64  `json:"versionCode"`
	Name string `json:"versionName"`
}

// bundledVersions reads the package name and version of each APK this wizard installs.
func bundledVersions() (map[string]appVersion, error) {
	dir, cleanup, err := apkFolder()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	out := map[string]appVersion{}
	for _, n := range apkNames {
		pkg, err := apk.OpenFile(filepath.Join(dir, n))
		if err != nil {
			return nil, fmt.Errorf("%s: %v", n, err)
		}
		m := pkg.Manifest()
		code, _ := m.VersionCode.Int32()
		name, _ := m.VersionName.String()
		out[pkg.PackageName()] = appVersion{int64(code), name}
		pkg.Close()
	}
	return out, nil
}

var (
	reVersionCode = regexp.MustCompile(`versionCode=(\d+)`)
	reVersionName = regexp.MustCompile(`versionName=(\S+)`)
)

// installedVersion is the version of pkg on the box, or nil when it isn't installed.
func installedVersion(a ADB, pkg string) *appVersion {
	out, _ := a.sh("dumpsys", "package", pkg)
	m := reVersionCode.FindStringSubmatch(out)
	if m == nil {
		return nil
	}
	code, _ := strconv.ParseInt(m[1], 10, 64)
	v := &appVersion{Code: code}
	if n := reVersionName.FindStringSubmatch(out); n != nil {
		v.Name = n[1]
	}
	return v
}

func deviceName(a ADB) string {
	n, _ := a.sh("settings", "get", "global", "device_name")
	n = strings.TrimSpace(n)
	if n == "null" {
		return ""
	}
	return n
}

type boxStatus struct {
	IP       string      `json:"ip"`
	Name     string      `json:"name,omitempty"`
	Error    string      `json:"error,omitempty"`
	Launcher *appVersion `json:"launcher,omitempty"`
	LiveTV   *appVersion `json:"livetv,omitempty"`
	Behind   bool        `json:"behind"`
}

func boxesStatus(ips []string) []boxStatus {
	want, _ := bundledVersions()
	out := make([]boxStatus, len(ips))
	var wg sync.WaitGroup
	for i, ip := range ips {
		wg.Add(1)
		go func(i int, ip string) {
			defer wg.Done()
			st := boxStatus{IP: ip}
			a, err := connect(ip, func(string) {})
			if err != nil {
				st.Error = "not reachable"
				out[i] = st
				return
			}
			st.Name = deviceName(a)
			st.Launcher = installedVersion(a, launcherPkg)
			st.LiveTV = installedVersion(a, livetvPkg)
			for pkg, have := range map[string]*appVersion{launcherPkg: st.Launcher, livetvPkg: st.LiveTV} {
				if w, ok := want[pkg]; ok && have != nil && w.Code > have.Code {
					st.Behind = true
				}
			}
			out[i] = st
		}(i, ip)
	}
	wg.Wait()
	return out
}

// updateApps installs any bundled app newer than the box's copy. App data (settings, Home layout) is kept.
func updateApps(a ADB, log logger) error {
	want, err := bundledVersions()
	if err != nil {
		return err
	}
	dir, cleanup, err := apkFolder()
	if err != nil {
		return err
	}
	defer cleanup()
	if installedVersion(a, launcherPkg) == nil {
		return errors.New("Vanilla Home isn't on this box yet; use Set up this box first")
	}
	pkgFile := map[string]string{livetvPkg: "vanilla-livetv.apk", launcherPkg: "vanilla-launcher.apk"}
	for _, pkg := range []string{livetvPkg, launcherPkg} {
		w := want[pkg]
		have := installedVersion(a, pkg)
		if have != nil && have.Code >= w.Code {
			log("  %s %s is up to date", pkg, have.Name)
			continue
		}
		log("  installing %s %s", pkg, w.Name)
		if out, err := a.run(5*time.Minute, "install", "-r", "-g", filepath.Join(dir, pkgFile[pkg])); err != nil || !strings.Contains(out, "Success") {
			return fmt.Errorf("installing %s failed: %v %s", pkg, err, strings.TrimSpace(out))
		}
	}
	allowSelfUpdate(a)
	homeButtonOn(a)
	return nil
}

// allowSelfUpdate lets Vanilla Home install app updates from Settings → System → Updates.
func allowSelfUpdate(a ADB) {
	_, _ = a.sh("appops", "set", launcherPkg, "REQUEST_INSTALL_PACKAGES", "allow")
}

// homeButtonOn (re)enables the accessibility service that sends the Home button to Vanilla Home.
func homeButtonOn(a ADB) {
	enabled, _ := a.sh("settings", "get", "secure", "enabled_accessibility_services")
	enabled = strings.TrimSpace(enabled)
	if !strings.Contains(enabled, homeButtonService) {
		if enabled == "" || enabled == "null" {
			enabled = homeButtonService
		} else {
			enabled += ":" + homeButtonService
		}
		_, _ = a.sh("settings", "put", "secure", "enabled_accessibility_services", enabled)
	}
	_, _ = a.sh("settings", "put", "secure", "accessibility_enabled", "1")
	_, _ = a.sh("pm", "grant", launcherPkg, "android.permission.WRITE_SECURE_SETTINGS")
}

// Home template: one TV's look, layout and dock, saved here so other or reset TVs can get it.

type homeTemplate struct {
	From    string          `json:"from"`
	SavedAt time.Time       `json:"savedAt"`
	Home    json.RawMessage `json:"home"`
}

func templatePath() string { return filepath.Join(dataDir, "home-template.json") }

func loadTemplate() *homeTemplate {
	b, err := os.ReadFile(templatePath())
	if err != nil {
		return nil
	}
	var t homeTemplate
	if json.Unmarshal(b, &t) != nil || len(t.Home) == 0 {
		return nil
	}
	return &t
}

const roomsPort = "8766"

// fetchHome asks Vanilla Home on the box for its Home settings (served on the Rooms port).
func fetchHome(ip string) (json.RawMessage, error) {
	host := normalizeTarget(ip)
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	c := http.Client{Timeout: 6 * time.Second}
	r, err := c.Get("http://" + net.JoinHostPort(host, roomsPort) + "/home")
	if err != nil {
		return nil, errors.New("couldn't reach Vanilla Home on that box; is it on and up to date?")
	}
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var probe struct {
		VanillaHome int `json:"vanillaHome"`
	}
	if json.Unmarshal(b, &probe) != nil || probe.VanillaHome == 0 {
		return nil, errors.New("that box's Vanilla Home is too old to share its Home; update its apps first")
	}
	return b, nil
}

func homeIntentArgs(t *homeTemplate) []string {
	if t == nil {
		return nil
	}
	return []string{"--es", "home_b64", base64.StdEncoding.EncodeToString(t.Home)}
}

func applyTemplate(a ADB, t *homeTemplate) error {
	args := append([]string{"am", "start", "-n", launcherPkg + "/.MainActivity"}, homeIntentArgs(t)...)
	out, err := a.sh(args...)
	if err == nil && strings.Contains(out, "Error") {
		err = errors.New(strings.TrimSpace(out))
	}
	return err
}

// updateBase is where boxes download app updates from this wizard, as reached by the browser
// that sent r; empty when that's this computer only.
func updateBase(r *http.Request) string {
	host := r.Host
	h := host
	if x, _, err := net.SplitHostPort(host); err == nil {
		h = x
	}
	if h == "" || h == "localhost" || strings.HasPrefix(h, "127.") || h == "::1" {
		return ""
	}
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + host + "/updates/"
}

func registerFleet(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/boxes/status", func(w http.ResponseWriter, r *http.Request) {
		var b struct{ IPs []string }
		_ = json.NewDecoder(r.Body).Decode(&b)
		want, _ := bundledVersions()
		writeJSON(w, map[string]any{"bundled": map[string]any{"launcher": want[launcherPkg], "livetv": want[livetvPkg]}, "boxes": boxesStatus(b.IPs), "template": loadTemplate()})
	})
	// Updates the apps on each box, then (optionally) sends the current settings. Each TV keeps its own Home.
	mux.HandleFunc("POST /api/boxes/update", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			IPs      []string `json:"ips"`
			Settings bool     `json:"settings"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		base := updateBase(r)
		stream(w, func(log logger) error {
			failed := 0
			for _, ip := range b.IPs {
				log("==> %s", ip)
				err := func() error {
					a, err := connect(ip, func(m string) { log("  %s", m) })
					if err != nil {
						return err
					}
					if err := updateApps(a, log); err != nil {
						return err
					}
					if b.Settings {
						return provision(ProvisionOpts{IP: ip, ConfigOnly: true, UpdateURL: base}, loadSetup(), func(f string, args ...any) { log("  "+f, args...) })
					}
					return nil
				}()
				if err != nil {
					failed++
					log("  ✗ %v", err)
				}
			}
			if failed > 0 {
				return fmt.Errorf("%d of %d boxes didn't update", failed, len(b.IPs))
			}
			log("All boxes updated.")
			return nil
		})
	})
	mux.HandleFunc("POST /api/home-template/save", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			IP, Name string
			Live     bool
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		home, err := fetchHome(b.IP)
		if err != nil {
			writeErr(w, err)
			return
		}
		if !b.Live {
			var m map[string]json.RawMessage
			if json.Unmarshal(home, &m) == nil {
				delete(m, "livetv")
				home, _ = json.Marshal(m)
			}
		}
		t := homeTemplate{From: b.Name, SavedAt: time.Now(), Home: home}
		if t.From == "" {
			t.From = b.IP
		}
		raw, _ := json.MarshalIndent(t, "", "  ")
		if err := os.WriteFile(templatePath(), raw, 0o600); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, t)
	})
	mux.HandleFunc("POST /api/home-template/clear", func(w http.ResponseWriter, r *http.Request) {
		_ = os.Remove(templatePath())
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/home-template/apply", func(w http.ResponseWriter, r *http.Request) {
		var b struct{ IPs []string }
		_ = json.NewDecoder(r.Body).Decode(&b)
		t := loadTemplate()
		if t == nil {
			writeErr(w, errors.New("no Home saved yet"))
			return
		}
		stream(w, func(log logger) error {
			for _, ip := range b.IPs {
				a, err := connect(ip, func(string) {})
				if err == nil {
					err = applyTemplate(a, t)
				}
				if err != nil {
					log("✗ %s: %v", ip, err)
					continue
				}
				log("✓ %s now has the Home from %s", ip, t.From)
			}
			return nil
		})
	})
	// Public (no sign-in): boxes check here for app updates. The APKs are the same ones published on GitHub.
	mux.HandleFunc("GET /updates/version.json", func(w http.ResponseWriter, r *http.Request) {
		want, err := bundledVersions()
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, want)
	})
	for _, n := range apkNames {
		n := n
		mux.HandleFunc("GET /updates/"+n, func(w http.ResponseWriter, r *http.Request) {
			dir, cleanup, err := apkFolder()
			if err != nil {
				writeErr(w, err)
				return
			}
			defer cleanup()
			w.Header().Set("Content-Type", "application/vnd.android.package-archive")
			http.ServeFile(w, r, filepath.Join(dir, n))
		})
	}
}
