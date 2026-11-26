// Onn Vanilla setup: a local web wizard that configures and provisions Android TV boxes.
package main

import (
	"embed"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

//go:embed web
var webFS embed.FS

//go:embed all:apks
var apkFS embed.FS

//go:embed bloat.txt
var bloatTxt string

var (
	apkDir  string
	dataDir string
)

func main() {
	listen := flag.String("listen", "127.0.0.1", "address to listen on (0.0.0.0 lets other computers on your network open the wizard)")
	port := flag.Int("port", 8765, "port to listen on")
	noBrowser := flag.Bool("no-browser", false, "don't open a browser")
	flag.StringVar(&apkDir, "apks", "", "folder with vanilla-launcher.apk / vanilla-livetv.apk (overrides the bundled ones)")
	flag.StringVar(&dataDir, "data", os.Getenv("ONN_VANILLA_DATA"), "folder for settings, the password and adb (default: your config folder/onn-vanilla)")
	resetPassword := flag.Bool("reset-password", false, "forget the wizard password; the next visit from your home network sets a new one")
	flag.Parse()

	if dataDir == "" {
		base, err := os.UserConfigDir()
		if err != nil {
			base, _ = os.UserHomeDir()
		}
		dataDir = filepath.Join(base, "onn-vanilla")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		log.Fatal(err)
	}
	if *resetPassword {
		if err := os.Remove(filepath.Join(dataDir, "auth.json")); err != nil && !os.IsNotExist(err) {
			log.Fatal(err)
		}
		fmt.Println("Password cleared. Open the wizard from your home network to set a new one.")
		return
	}

	guard := newAuth(dataDir)
	mux := http.NewServeMux()
	guard.register(mux)
	web, _ := fs.Sub(webFS, "web")
	mux.Handle("/", http.FileServer(http.FS(web)))
	registerAPI(mux)

	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", *listen, *port))
	if err != nil {
		ln, err = net.Listen("tcp", *listen+":0")
		if err != nil {
			log.Fatal(err)
		}
	}
	p := ln.Addr().(*net.TCPAddr).Port
	host := "127.0.0.1"
	if *listen != "127.0.0.1" && *listen != "localhost" {
		host = lanIP()
	}
	url := fmt.Sprintf("http://%s:%d/", host, p)
	fmt.Println("Onn Vanilla setup is running. Open this in your browser:")
	fmt.Println("  " + url)
	if !guard.hasPassword() {
		fmt.Println("First visit: choose a password for the wizard.")
	}
	fmt.Println("Settings are saved in", dataDir)
	fmt.Println("Press Ctrl+C to quit.")
	if !*noBrowser && host == "127.0.0.1" {
		openBrowser(url)
	}
	log.Fatal(http.Serve(ln, guard.protect(mux)))
}

func lanIP() string {
	c, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).IP.String()
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

type bloatPkg struct {
	Package string `json:"package"`
	Label   string `json:"label"`
}

func bloatList() []bloatPkg {
	var out []bloatPkg
	for _, line := range strings.Split(bloatTxt, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		pkg, label, _ := strings.Cut(line, "|")
		out = append(out, bloatPkg{strings.TrimSpace(pkg), strings.TrimSpace(label)})
	}
	return out
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, err error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}
