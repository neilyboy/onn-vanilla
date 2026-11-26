package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The wizard is protected by one password, chosen on the first visit from the home network.
// Signed-in browsers keep a signed cookie; changing the password signs everyone out.

const (
	sessionCookie = "ov_session"
	sessionDays   = 180
	pbkdf2Iter    = 210000
)

type authFile struct {
	Salt   string `json:"salt"`
	Hash   string `json:"hash"`
	Iter   int    `json:"iter"`
	Secret string `json:"secret"`
}

type auth struct {
	mu    sync.Mutex
	path  string
	f     *authFile
	fails map[string][]time.Time
}

func newAuth(dir string) *auth {
	a := &auth{path: filepath.Join(dir, "auth.json"), fails: map[string][]time.Time{}}
	if b, err := os.ReadFile(a.path); err == nil {
		var f authFile
		if json.Unmarshal(b, &f) == nil && f.Hash != "" {
			a.f = &f
		}
	}
	return a
}

func pbkdf2SHA256(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	var out []byte
	for block := uint32(1); len(out) < keyLen; block++ {
		prf.Reset()
		prf.Write(salt)
		var n [4]byte
		binary.BigEndian.PutUint32(n[:], block)
		prf.Write(n[:])
		u := prf.Sum(nil)
		t := append([]byte(nil), u...)
		for i := 1; i < iter; i++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (a *auth) hasPassword() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.f != nil
}

func (a *auth) setPassword(pw string) error {
	salt := randHex(16)
	f := &authFile{Salt: salt, Iter: pbkdf2Iter, Secret: randHex(32),
		Hash: hex.EncodeToString(pbkdf2SHA256([]byte(pw), []byte(salt), pbkdf2Iter, 32))}
	b, _ := json.MarshalIndent(f, "", "  ")
	if err := os.WriteFile(a.path, b, 0o600); err != nil {
		return err
	}
	a.mu.Lock()
	a.f = f
	a.mu.Unlock()
	return nil
}

func (a *auth) check(pw string) bool {
	a.mu.Lock()
	f := a.f
	a.mu.Unlock()
	if f == nil {
		return false
	}
	got := pbkdf2SHA256([]byte(pw), []byte(f.Salt), f.Iter, 32)
	want, _ := hex.DecodeString(f.Hash)
	return subtle.ConstantTimeCompare(got, want) == 1
}

func (a *auth) sign(exp string) string {
	a.mu.Lock()
	secret := a.f.Secret
	a.mu.Unlock()
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(exp))
	return hex.EncodeToString(m.Sum(nil))
}

func (a *auth) signedIn(r *http.Request) bool {
	if !a.hasPassword() {
		return false
	}
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return false
	}
	exp, sig, ok := strings.Cut(c.Value, ".")
	if !ok {
		return false
	}
	t, err := strconv.ParseInt(exp, 10, 64)
	if err != nil || time.Now().Unix() > t {
		return false
	}
	return hmac.Equal([]byte(sig), []byte(a.sign(exp)))
}

func isHTTPS(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

func (a *auth) startSession(w http.ResponseWriter, r *http.Request) {
	exp := strconv.FormatInt(time.Now().Add(sessionDays*24*time.Hour).Unix(), 10)
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: exp + "." + a.sign(exp), Path: "/", HttpOnly: true,
		Secure: isHTTPS(r), SameSite: http.SameSiteLaxMode, MaxAge: sessionDays * 24 * 3600})
}

var cgnat = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}

func privateIP(ip net.IP) bool {
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || cgnat.Contains(ip))
}

func remoteIP(r *http.Request) net.IP {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	return net.ParseIP(host)
}

// clientKey identifies a browser for login throttling, looking through a reverse proxy on the local network.
func clientKey(r *http.Request) string {
	ip := remoteIP(r)
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" && privateIP(ip) {
		first, _, _ := strings.Cut(xff, ",")
		return strings.TrimSpace(first)
	}
	return ip.String()
}

// fromHomeNetwork is true for direct connections from the local network (not through a reverse proxy),
// the only place the first password may be set.
func fromHomeNetwork(r *http.Request) bool {
	return r.Header.Get("X-Forwarded-For") == "" && r.Header.Get("Forwarded") == "" && privateIP(remoteIP(r))
}

func (a *auth) lockedOut(key string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	recent := a.fails[key][:0]
	for _, t := range a.fails[key] {
		if time.Since(t) < 15*time.Minute {
			recent = append(recent, t)
		}
	}
	a.fails[key] = recent
	return len(recent) >= 5
}

func (a *auth) failed(key string) {
	a.mu.Lock()
	a.fails[key] = append(a.fails[key], time.Now())
	a.mu.Unlock()
	time.Sleep(700 * time.Millisecond)
}

// sameOrigin rejects cross-site form posts: a browser always sends Origin on them.
func sameOrigin(r *http.Request) bool {
	o := r.Header.Get("Origin")
	if o == "" {
		return true
	}
	u, err := url.Parse(o)
	if err != nil {
		return false
	}
	return u.Host == r.Host || (r.Header.Get("X-Forwarded-Host") != "" && u.Host == r.Header.Get("X-Forwarded-Host"))
}

func readPassword(r *http.Request, field string) string {
	var body map[string]string
	_ = json.NewDecoder(http.MaxBytesReader(nil, r.Body, 4096)).Decode(&body)
	return body[field]
}

func (a *auth) register(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/auth", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"hasPassword": a.hasPassword(), "signedIn": a.signedIn(r), "canSetup": fromHomeNetwork(r)})
	})
	mux.HandleFunc("POST /api/auth/setup", func(w http.ResponseWriter, r *http.Request) {
		if a.hasPassword() {
			authErr(w, http.StatusConflict, "A password is already set. Sign in instead.")
			return
		}
		if !fromHomeNetwork(r) {
			authErr(w, http.StatusForbidden, "Set the password from your home network first, by opening the wizard's own address (http://<server>:8765).")
			return
		}
		pw := readPassword(r, "password")
		if len(pw) < 8 {
			authErr(w, http.StatusBadRequest, "Use at least 8 characters.")
			return
		}
		if err := a.setPassword(pw); err != nil {
			authErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		a.startSession(w, r)
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /api/auth/login", func(w http.ResponseWriter, r *http.Request) {
		key := clientKey(r)
		if a.lockedOut(key) {
			authErr(w, http.StatusTooManyRequests, "Too many wrong passwords. Try again in 15 minutes.")
			return
		}
		if !a.check(readPassword(r, "password")) {
			a.failed(key)
			authErr(w, http.StatusUnauthorized, "Wrong password.")
			return
		}
		a.startSession(w, r)
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /api/auth/logout", func(w http.ResponseWriter, r *http.Request) {
		http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true})
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /api/auth/password", func(w http.ResponseWriter, r *http.Request) {
		if !a.signedIn(r) {
			authErr(w, http.StatusUnauthorized, "Sign in first.")
			return
		}
		var body map[string]string
		_ = json.NewDecoder(http.MaxBytesReader(nil, r.Body, 4096)).Decode(&body)
		if !a.check(body["current"]) {
			a.failed(clientKey(r))
			authErr(w, http.StatusUnauthorized, "Current password is wrong.")
			return
		}
		if len(body["password"]) < 8 {
			authErr(w, http.StatusBadRequest, "Use at least 8 characters.")
			return
		}
		if err := a.setPassword(body["password"]); err != nil {
			authErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		a.startSession(w, r)
		writeJSON(w, map[string]bool{"ok": true})
	})
}

// protect lets the sign-in page and its API through and requires a session for everything else.
func (a *auth) protect(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead && !sameOrigin(r) {
			authErr(w, http.StatusForbidden, "Cross-site request blocked.")
			return
		}
		p := r.URL.Path
		if p == "/login" || p == "/login.html" || p == "/style.css" || strings.HasPrefix(p, "/api/auth") || a.signedIn(r) {
			if p == "/login" {
				r.URL.Path = "/login.html"
			}
			next.ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(p, "/api/") {
			authErr(w, http.StatusUnauthorized, "Sign in first.")
			return
		}
		http.Redirect(w, r, "/login", http.StatusFound)
	})
}

func authErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
