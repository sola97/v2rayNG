package main

import "testing"

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
