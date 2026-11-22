package main

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func adbName() string {
	if runtime.GOOS == "windows" {
		return "adb.exe"
	}
	return "adb"
}

// findADB prefers the copy this app downloaded, then adb on PATH, then common SDK folders.
func findADB() string {
	own := filepath.Join(dataDir, "platform-tools", adbName())
	if fileExists(own) {
		return own
	}
	if p, err := exec.LookPath("adb"); err == nil {
		return p
	}
	home, _ := os.UserHomeDir()
	for _, p := range []string{
		filepath.Join(home, "platform-tools", adbName()),
		filepath.Join(home, "Android", "platform-tools", adbName()),
		filepath.Join(home, "Android", "Sdk", "platform-tools", adbName()),
		filepath.Join(home, "Library", "Android", "sdk", "platform-tools", adbName()),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", adbName()),
	} {
		if fileExists(p) {
			return p
		}
	}
	return ""
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// downloadADB fetches Google's official platform-tools for this OS into the data folder.
func downloadADB() (string, error) {
	osName := map[string]string{"windows": "windows", "darwin": "darwin", "linux": "linux"}[runtime.GOOS]
	if osName == "" {
		return "", fmt.Errorf("no platform-tools build for %s", runtime.GOOS)
	}
	url := "https://dl.google.com/android/repository/platform-tools-latest-" + osName + ".zip"
	resp, err := http.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("download failed: %s", resp.Status)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", err
	}
	for _, f := range zr.File {
		dst := filepath.Join(dataDir, filepath.FromSlash(f.Name))
		if !strings.HasPrefix(dst, filepath.Clean(dataDir)+string(os.PathSeparator)) {
			continue
		}
		if f.FileInfo().IsDir() {
			_ = os.MkdirAll(dst, 0o755)
			continue
		}
		_ = os.MkdirAll(filepath.Dir(dst), 0o755)
		rc, err := f.Open()
		if err != nil {
			return "", err
		}
		out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, f.Mode()|0o600)
		if err == nil {
			_, err = io.Copy(out, rc)
			out.Close()
		}
		rc.Close()
		if err != nil {
			return "", err
		}
	}
	p := filepath.Join(dataDir, "platform-tools", adbName())
	_ = os.Chmod(p, 0o755)
	if !fileExists(p) {
		return "", errors.New("adb not found in download")
	}
	return p, nil
}

type ADB struct {
	path   string
	target string
}

func (a ADB) run(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	full := args
	if a.target != "" {
		full = append([]string{"-s", a.target}, args...)
	}
	out, err := exec.CommandContext(ctx, a.path, full...).CombinedOutput()
	s := strings.ReplaceAll(string(out), "\r", "")
	if err != nil && strings.TrimSpace(s) != "" {
		err = fmt.Errorf("%s", strings.TrimSpace(s))
	}
	return s, err
}

// sh runs one command line on the box; args are shell-quoted for the remote /system/bin/sh.
func (a ADB) sh(args ...string) (string, error) {
	q := make([]string, len(args))
	for i, s := range args {
		q[i] = shQuote(s)
	}
	out, err := a.run(60*time.Second, "shell", strings.Join(q, " "))
	return strings.TrimSpace(out), err
}

func shQuote(s string) string {
	if s == "" {
		return "''"
	}
	safe := true
	for _, c := range s {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("-_./:=+@%,", c)) {
			safe = false
			break
		}
	}
	if safe {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func normalizeTarget(ip string) string {
	ip = strings.TrimSpace(ip)
	if ip == "" {
		return ""
	}
	if !strings.Contains(ip, ":") {
		ip += ":5555"
	}
	return ip
}

// connect runs `adb connect` and waits until the box authorizes this computer.
func connect(ip string, log func(string)) (ADB, error) {
	path := findADB()
	if path == "" {
		log("adb not found, downloading Google platform-tools...")
		p, err := downloadADB()
		if err != nil {
			return ADB{}, err
		}
		path = p
	}
	t := normalizeTarget(ip)
	if t == "" {
		return ADB{}, errors.New("enter the box's IP address")
	}
	a := ADB{path: path}
	out, _ := a.run(20*time.Second, "connect", t)
	if strings.Contains(out, "failed") || strings.Contains(out, "cannot") {
		return ADB{}, fmt.Errorf("could not reach %s: %s (is the box on and USB debugging enabled?)", t, strings.TrimSpace(out))
	}
	a.target = t
	asked := false
	for i := 0; i < 60; i++ {
		st, _ := a.run(5*time.Second, "get-state")
		st = strings.TrimSpace(st)
		if st == "device" {
			return a, nil
		}
		if !asked {
			log("Waiting for the box... on the TV choose \"Always allow from this computer\" and press OK.")
			asked = true
		}
		if strings.Contains(st, "offline") || i%10 == 9 {
			_, _ = a.run(10*time.Second, "reconnect")
			_, _ = ADB{path: path}.run(10*time.Second, "connect", t)
		}
		time.Sleep(time.Second)
	}
	return ADB{}, errors.New("the box didn't authorize this computer; accept the prompt on the TV and try again")
}

// pair runs `adb pair` for Android 11+ wireless debugging ("Pair device with pairing code").
func pair(hostport, code string) (string, error) {
	path := findADB()
	if path == "" {
		p, err := downloadADB()
		if err != nil {
			return "", err
		}
		path = p
	}
	hostport, code = strings.TrimSpace(hostport), strings.TrimSpace(code)
	if !strings.Contains(hostport, ":") || len(code) < 6 {
		return "", errors.New("enter the IP:port and 6-digit code shown in the pairing popup")
	}
	out, _ := ADB{path: path}.run(30*time.Second, "pair", hostport, code)
	out = strings.TrimSpace(out)
	if !strings.Contains(out, "Successfully paired") {
		return out, fmt.Errorf("pairing failed: %s", out)
	}
	return out, nil
}
