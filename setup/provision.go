package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	launcherPkg = "tv.vanilla.launcher"
	livetvPkg   = "tv.vanilla.live"
)

var apkNames = []string{"vanilla-launcher.apk", "vanilla-livetv.apk"}

type BoxInfo struct {
	Target    string     `json:"target"`
	Model     string     `json:"model"`
	Maker     string     `json:"maker"`
	Android   string     `json:"android"`
	SDK       string     `json:"sdk"`
	Serial    string     `json:"serial"`
	SetupDone bool       `json:"setupDone"`
	Launcher  bool       `json:"launcherInstalled"`
	LiveTV    bool       `json:"livetvInstalled"`
	Removable []bloatPkg `json:"removable"`
	UserApps  []string   `json:"userApps"`
	HasBackup bool       `json:"hasBackup"`
	PlayStore bool       `json:"playStore"`
	Family    string     `json:"family"`
	Known     bool       `json:"known"`
	Homes     []string   `json:"homes"`
}

// knownDevice reports devices whose removal list has been tested; others get nothing pre-selected.
func knownDevice(maker, model string) bool {
	m := strings.ToLower(maker + " " + model)
	return strings.Contains(m, "onn")
}

// homeApps lists every installed home screen except Vanilla Home, so none of them is ever removed.
func homeApps(a ADB) []string {
	out, _ := a.sh("cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
	seen := map[string]bool{}
	var homes []string
	for _, l := range strings.Split(out, "\n") {
		l = strings.TrimSpace(l)
		if i := strings.Index(l, "/"); i > 0 && !strings.Contains(l, " ") {
			p := l[:i]
			if p != launcherPkg && !seen[p] {
				seen[p] = true
				homes = append(homes, p)
			}
		}
	}
	return homes
}

func inspect(a ADB) (BoxInfo, error) {
	prop := func(k string) string { v, _ := a.sh("getprop", k); return v }
	info := BoxInfo{
		Target: a.target, Model: prop("ro.product.model"), Maker: prop("ro.product.manufacturer"),
		Android: prop("ro.build.version.release"), SDK: prop("ro.build.version.sdk"), Serial: prop("ro.serialno"),
	}
	info.Removable, info.UserApps = []bloatPkg{}, []string{}
	sc, _ := a.sh("settings", "get", "secure", "user_setup_complete")
	info.SetupDone = sc == "1"
	pkgs, err := packages(a, "")
	if err != nil {
		return info, err
	}
	for _, b := range bloatList() {
		if pkgs[b.Package] {
			info.Removable = append(info.Removable, b)
		}
	}
	info.Launcher, info.LiveTV, info.PlayStore = pkgs[launcherPkg], pkgs[livetvPkg], pkgs["com.android.vending"]
	switch {
	case pkgs["com.google.android.apps.tv.launcherx"]:
		info.Family = "Google TV"
	case pkgs["com.google.android.tvlauncher"] || pkgs["com.google.android.leanbacklauncher"]:
		info.Family = "Android TV"
	case pkgs["com.amazon.tv.launcher"]:
		info.Family = "Fire TV"
	default:
		info.Family = "Android"
	}
	info.Known = knownDevice(info.Maker, info.Model)
	info.Homes = homeApps(a)
	user, _ := packages(a, "-3")
	for p := range user {
		info.UserApps = append(info.UserApps, p)
	}
	sort.Strings(info.UserApps)
	info.HasBackup = latestBackup(info.Serial) != ""
	return info, nil
}

func packages(a ADB, flag string) (map[string]bool, error) {
	args := []string{"pm", "list", "packages"}
	if flag != "" {
		args = append(args, flag)
	}
	out, err := a.sh(args...)
	if err != nil {
		return nil, err
	}
	m := map[string]bool{}
	for _, l := range strings.Split(out, "\n") {
		if p := strings.TrimPrefix(strings.TrimSpace(l), "package:"); p != "" {
			m[p] = true
		}
	}
	return m, nil
}

type ProvisionOpts struct {
	IP              string   `json:"ip"`
	DryRun          bool     `json:"dryRun"`
	Remove          []string `json:"remove"`
	RemovePlayStore bool     `json:"removePlayStore"`
	Reboot          bool     `json:"reboot"`
	ConfigOnly      bool     `json:"configOnly"`
	ResetHome       bool     `json:"resetHome"`
	UpdateURL       string   `json:"-"`
}

type logger func(format string, args ...any)

func provision(o ProvisionOpts, s Setup, log logger) error {
	cfg, err := s.boxConfig()
	if err != nil {
		return err
	}
	log("==> Connecting to %s", normalizeTarget(o.IP))
	a, err := connect(o.IP, func(m string) { log("  %s", m) })
	if err != nil {
		return err
	}
	info, err := inspect(a)
	if err != nil {
		return err
	}
	log("  %s %s, Android %s (SDK %s), serial %s", info.Maker, info.Model, info.Android, info.SDK, info.Serial)
	if !info.SetupDone {
		return errors.New("finish the box's on-screen setup (Wi-Fi + Google sign-in or skip) first")
	}
	do := func(desc string, args ...string) error {
		if o.DryRun {
			log("  [dry run] %s", desc)
			return nil
		}
		out, err := a.sh(args...)
		if err != nil {
			return fmt.Errorf("%s: %v", desc, err)
		}
		if strings.Contains(out, "Failure") || strings.Contains(out, "Error") {
			return fmt.Errorf("%s: %s", desc, out)
		}
		return nil
	}

	if !o.ConfigOnly {
		log("==> Backing up the package list")
		b, err := backup(a, info.Serial)
		if err != nil {
			return err
		}
		log("  saved %s", b)

		log("==> Installing apps")
		dir, cleanup, err := apkFolder()
		if err != nil {
			return err
		}
		defer cleanup()
		for _, n := range apkNames {
			log("  %s", n)
			if o.DryRun {
				continue
			}
			if out, err := a.run(5*time.Minute, "install", "-r", "-g", filepath.Join(dir, n)); err != nil || !strings.Contains(out, "Success") {
				return fmt.Errorf("installing %s failed: %v %s", n, err, strings.TrimSpace(out))
			}
		}
		if !o.DryRun {
			if p, _ := packages(a, ""); !p[launcherPkg] {
				return errors.New("launcher didn't install; stopping before removing anything")
			}
		}

		log("==> Removing apps (per-user, reversible)")
		remove := append([]string{}, o.Remove...)
		if o.RemovePlayStore {
			remove = append(remove, "com.android.vending")
		}
		allowed := map[string]bool{"com.android.vending": true}
		for _, b := range bloatList() {
			allowed[b.Package] = true
		}
		userPkgs, _ := packages(a, "-3")
		for p := range userPkgs {
			if p != launcherPkg && p != livetvPkg {
				allowed[p] = true
			}
		}
		for _, p := range append(append([]string{}, neverRemove...), info.Homes...) {
			allowed[p] = false
		}
		present, _ := packages(a, "")
		for _, p := range remove {
			if !allowed[p] || !present[p] {
				continue
			}
			if err := do("remove "+p, "pm", "uninstall", "-k", "--user", "0", p); err != nil {
				if err2 := do("disable "+p, "pm", "disable-user", "--user", "0", p); err2 != nil {
					log("  ! could not remove %s", p)
					continue
				}
			}
			if !o.DryRun {
				log("  - %s", p)
			}
		}

		log("==> Making Vanilla Home the home screen")
		_ = do("set home activity", "cmd", "package", "set-home-activity", launcherPkg+"/.MainActivity")
		_ = do("set HOME role", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.HOME", launcherPkg)
		for _, p := range stockHomes {
			if present[p] {
				_ = do("restore "+p, "cmd", "package", "install-existing", p)
			}
		}
		if !o.DryRun {
			log("  Home button service, app updates from the TV")
			homeButtonOn(a)
			allowSelfUpdate(a)
		}
	}

	log("==> Sending your settings to the box")
	raw, _ := json.Marshal(cfg)
	b64 := base64.StdEncoding.EncodeToString(raw)
	live := cfg["livetv"].(map[string]any)
	// A TV's own look, layout and dock stay unless asked to reset them (or it's a new box). A reset or
	// new box gets the saved Home template when there is one, else the wizard's defaults.
	keepHome := info.Launcher && !o.ResetHome
	launcherArgs := []string{"am", "start", "-n", launcherPkg + "/.MainActivity",
		"--es", "config_url", s.RemoteConfigURL, "--es", "config_b64", b64,
		"--ez", "keep_home", strconv.FormatBool(keepHome), "--ez", "reset_dock", strconv.FormatBool(!keepHome)}
	if o.UpdateURL != "" {
		launcherArgs = append(launcherArgs, "--es", "update_url", o.UpdateURL)
	}
	if t := loadTemplate(); t != nil && !keepHome {
		log("  using the Home saved from %s", t.From)
		launcherArgs = append(launcherArgs, homeIntentArgs(t)...)
	} else if keepHome {
		log("  keeping this TV's own Home (theme, layout, dock)")
	}
	if err := do("configure Live TV", "am", "start", "-n", livetvPkg+"/.MainActivity",
		"--es", "config_url", s.RemoteConfigURL, "--es", "m3u_url", live["m3u_url"].(string),
		"--es", "epg_url", live["epg_url"].(string), "--es", "config_b64", b64); err != nil {
		return err
	}
	if !o.DryRun {
		time.Sleep(3 * time.Second)
	}
	if err := do("configure launcher", launcherArgs...); err != nil {
		return err
	}

	if !o.ConfigOnly {
		log("==> System tweaks")
		for _, k := range []string{"window_animation_scale", "transition_animation_scale", "animator_duration_scale"} {
			_ = do("faster animations ("+k+")", "settings", "put", "global", k, "0.5")
		}
		if s.Timezone != "" {
			_ = do("time zone "+s.Timezone, "service", "call", "alarm", "3", "s16", s.Timezone)
		}
		if o.Reboot && !o.DryRun {
			log("==> Rebooting")
			_, _ = a.run(20*time.Second, "reboot")
		}
	}
	log("Done.")
	return nil
}

func backupDir() string { return filepath.Join(dataDir, "backups") }

func backup(a ADB, serial string) (string, error) {
	_ = os.MkdirAll(backupDir(), 0o700)
	if serial == "" {
		serial = strings.NewReplacer(":", "_", ".", "_").Replace(a.target)
	}
	name := fmt.Sprintf("%s-%s.packages.txt", serial, time.Now().Format("20060102-150405"))
	out, err := a.sh("pm", "list", "packages")
	if err != nil {
		return "", err
	}
	p := filepath.Join(backupDir(), name)
	return p, os.WriteFile(p, []byte(out+"\n"), 0o600)
}

// latestBackup returns the oldest backup for a box: the state before it was first provisioned.
func latestBackup(serial string) string {
	if serial == "" {
		return ""
	}
	m, _ := filepath.Glob(filepath.Join(backupDir(), serial+"-*.packages.txt"))
	if len(m) == 0 {
		return ""
	}
	sort.Strings(m)
	return m[0]
}

// restore reinstalls (for user 0) every package listed in the box's first backup.
func restore(ip string, log logger) error {
	a, err := connect(ip, func(m string) { log("  %s", m) })
	if err != nil {
		return err
	}
	serial, _ := a.sh("getprop", "ro.serialno")
	b := latestBackup(serial)
	if b == "" {
		return errors.New("no backup found for this box on this computer")
	}
	log("==> Restoring from %s", filepath.Base(b))
	data, _ := os.ReadFile(b)
	now, _ := packages(a, "")
	for _, l := range strings.Split(string(data), "\n") {
		p := strings.TrimPrefix(strings.TrimSpace(l), "package:")
		if p == "" || now[p] {
			continue
		}
		if _, err := a.sh("cmd", "package", "install-existing", "--user", "0", p); err == nil {
			log("  + %s", p)
		}
	}
	disabled, _ := packages(a, "-d")
	for p := range disabled {
		if strings.Contains(string(data), "package:"+p+"\n") {
			_, _ = a.sh("pm", "enable", p)
			log("  enabled %s", p)
		}
	}
	log("Done. Pick a home screen with the Home button if asked; reboot the box to finish.")
	return nil
}

// apkFolder returns a folder containing the APKs: --apks, ./apks next to the binary, or the bundled copies.
func apkFolder() (string, func(), error) {
	has := func(d string) bool {
		for _, n := range apkNames {
			if !fileExists(filepath.Join(d, n)) {
				return false
			}
		}
		return true
	}
	noop := func() {}
	if apkDir != "" {
		if has(apkDir) {
			return apkDir, noop, nil
		}
		return "", noop, fmt.Errorf("%s doesn't contain %v", apkDir, apkNames)
	}
	if exe, err := os.Executable(); err == nil {
		if d := filepath.Join(filepath.Dir(exe), "apks"); has(d) {
			return d, noop, nil
		}
	}
	tmp, err := os.MkdirTemp("", "onn-vanilla-apks")
	if err != nil {
		return "", noop, err
	}
	cleanup := func() { os.RemoveAll(tmp) }
	for _, n := range apkNames {
		b, err := fs.ReadFile(apkFS, "apks/"+n)
		if err != nil {
			cleanup()
			return "", noop, errors.New("this build has no bundled APKs; download a release build or pass --apks <folder>")
		}
		if err := os.WriteFile(filepath.Join(tmp, n), b, 0o644); err != nil {
			cleanup()
			return "", noop, err
		}
	}
	return tmp, cleanup, nil
}

const homeButtonService = launcherPkg + "/" + launcherPkg + ".HomeButtonService"

// Google TV loops on a setup-recovery screen without its stock home, so these always stay;
// Vanilla Home's accessibility service redirects the Home button instead.
var stockHomes = []string{"com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher"}

var neverRemove = append([]string{"com.google.android.tungsten.setupwraith", "com.android.tv.settings"}, stockHomes...)
