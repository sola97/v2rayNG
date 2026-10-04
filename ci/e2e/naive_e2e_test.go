package main

import (
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestXrayConfigLeavesUoTVersionForCoreDefault(t *testing.T) {
	config := xrayConfig(443, 10001, 10002, 10003, 10004, "test-ca")
	outbounds := config["outbounds"].([]any)
	outbound := outbounds[0].(map[string]any)
	settings := outbound["settings"].(map[string]any)
	udpOverTCP := settings["udpOverTcp"].(map[string]any)

	if udpOverTCP["enabled"] != true {
		t.Fatal("UDP over TCP must be enabled in the interoperability test")
	}
	if _, exists := udpOverTCP["version"]; exists {
		t.Fatal("the E2E configuration must omit version so Xray's v2 default is exercised")
	}

	mux := outbound["mux"].(map[string]any)
	if mux["enabled"] != false {
		t.Fatal("Xray mux must stay disabled for the native Naive outbound")
	}
}

func TestSingBoxConfigUsesNaiveInbound(t *testing.T) {
	config := singBoxConfig(443, "server.pem", "server.key")
	inbounds := config["inbounds"].([]any)
	inbound := inbounds[0].(map[string]any)

	if inbound["type"] != "naive" {
		t.Fatalf("unexpected inbound type: %v", inbound["type"])
	}
	if inbound["network"] != "tcp" {
		t.Fatalf("unexpected Naive transport network: %v", inbound["network"])
	}
}

func TestWaitForTCPUnavailableDetectsStoppedListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := waitForTCPUnavailable(port, 200*time.Millisecond); err == nil {
		t.Fatal("waitForTCPUnavailable succeeded while the listener was accepting connections")
	}
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	if err := waitForTCPUnavailable(port, time.Second); err != nil {
		t.Fatalf("waitForTCPUnavailable did not observe the closed listener: %v", err)
	}
}

func TestRecoveryResultReportsRecoveryAndDefaultUoT(t *testing.T) {
	result := testResult{
		Passed:                    true,
		UDPOverTCPVersion:         2,
		UDPVersionSource:          "defaulted by Xray because udpOverTcp.version was omitted",
		ServerOutageDetected:      true,
		TCPDuringServerOutage:     "failed as expected",
		TCPAfterServerRestart:     "passed",
		UDPAfterServerRestart:     "passed",
		NaiveEndpointReused:       true,
		XrayRestarted:             false,
		XrayPID:                   100,
		XrayPIDAfterServerRestart: 100,
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatal(err)
	}
	for key, want := range map[string]any{
		"udpOverTcpVersion":         float64(2),
		"serverOutageDetected":      true,
		"tcpDuringServerOutage":     "failed as expected",
		"tcpAfterServerRestart":     "passed",
		"udpAfterServerRestart":     "passed",
		"naiveEndpointReused":       true,
		"xrayRestarted":             false,
		"xrayPid":                   float64(100),
		"xrayPidAfterServerRestart": float64(100),
	} {
		if got[key] != want {
			t.Errorf("%s = %v, want %v", key, got[key], want)
		}
	}
	if got["udpVersionSource"] != result.UDPVersionSource {
		t.Errorf("udpVersionSource = %v, want %q", got["udpVersionSource"], result.UDPVersionSource)
	}
}
