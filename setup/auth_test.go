package main

import (
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPBKDF2Vector(t *testing.T) {
	// RFC 7914 section 11 PBKDF2-HMAC-SHA256 test vector.
	got := hex.EncodeToString(pbkdf2SHA256([]byte("passwd"), []byte("salt"), 1, 64))
	if !strings.HasPrefix(got, "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc") {
		t.Fatal(got)
	}
}

func TestAuthFlow(t *testing.T) {
	a := newAuth(t.TempDir())
	mux := http.NewServeMux()
	a.register(mux)
	mux.HandleFunc("GET /api/secret", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	h := a.protect(mux)
	do := func(method, path, body string, hdr map[string]string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.RemoteAddr = "192.168.1.20:5000"
		for k, v := range hdr {
			r.Header.Set(k, v)
		}
		for _, c := range cookies {
			r.AddCookie(c)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := do("GET", "/api/secret", "", nil); w.Code != 401 {
		t.Fatal("unauthenticated API allowed", w.Code)
	}
	if w := do("GET", "/", "", nil); w.Code != 302 {
		t.Fatal("page not redirected", w.Code)
	}
	if w := do("POST", "/api/auth/setup", `{"password":"longenough"}`, map[string]string{"X-Forwarded-For": "8.8.8.8"}); w.Code != 403 {
		t.Fatal("setup through proxy allowed", w.Code)
	}
	if w := do("POST", "/api/auth/setup", `{"password":"short"}`, nil); w.Code != 400 {
		t.Fatal("short password allowed", w.Code)
	}
	w := do("POST", "/api/auth/setup", `{"password":"longenough"}`, nil)
	if w.Code != 200 {
		t.Fatal("setup failed", w.Code, w.Body.String())
	}
	sess := w.Result().Cookies()[0]
	if w := do("GET", "/api/secret", "", nil, sess); w.Code != 200 {
		t.Fatal("session rejected", w.Code)
	}
	if w := do("POST", "/api/auth/setup", `{"password":"another1"}`, nil); w.Code != 409 {
		t.Fatal("second setup allowed", w.Code)
	}
	if w := do("POST", "/api/auth/login", `{"password":"nope"}`, nil); w.Code != 401 {
		t.Fatal("wrong password accepted", w.Code)
	}
	if w := do("POST", "/api/auth/login", `{"password":"longenough"}`, map[string]string{"Origin": "http://evil.example"}); w.Code != 403 {
		t.Fatal("cross-site post allowed", w.Code)
	}
	if w := do("POST", "/api/auth/login", `{"password":"longenough"}`, nil); w.Code != 200 {
		t.Fatal("login failed", w.Code)
	}
	forged := &http.Cookie{Name: sessionCookie, Value: "9999999999.deadbeef"}
	if w := do("GET", "/api/secret", "", nil, forged); w.Code != 401 {
		t.Fatal("forged session accepted", w.Code)
	}
	if w := do("POST", "/api/auth/password", `{"current":"longenough","password":"newpassword"}`, nil, sess); w.Code != 200 {
		t.Fatal("password change failed", w.Code)
	}
	if w := do("GET", "/api/secret", "", nil, sess); w.Code != 401 {
		t.Fatal("old session survived password change", w.Code)
	}
}
