package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

const (
	testServerName = "naive.test"
	testUsername   = "naive-e2e"
	testPassword   = "native-uot-v2"
)

type options struct {
	xrayPath        string
	singBoxPath     string
	outputPath      string
	xrayRevision    string
	singBoxRevision string
}

type testResult struct {
	Passed            bool   `json:"passed"`
	TCP               string `json:"tcp"`
	UDPOverTCP        string `json:"udpOverTcp"`
	UDPOverTCPVersion int    `json:"udpOverTcpVersion"`
	UDPVersionSource  string `json:"udpVersionSource"`
	XrayRevision      string `json:"xrayRevision"`
	SingBoxRevision   string `json:"singBoxRevision"`
	CompletedAt       string `json:"completedAt"`
}

type managedProcess struct {
	command *exec.Cmd
	logFile *os.File
	logPath string
	once    sync.Once
}

func main() {
	config := parseOptions()
	if err := run(config); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func parseOptions() options {
	var config options
	flag.StringVar(&config.xrayPath, "xray", "", "path to the Xray executable")
	flag.StringVar(&config.singBoxPath, "sing-box", "", "path to the sing-box executable")
	flag.StringVar(&config.outputPath, "output", "naive-e2e-result.json", "result JSON path")
	flag.StringVar(&config.xrayRevision, "xray-revision", "unknown", "Xray Git revision")
	flag.StringVar(&config.singBoxRevision, "sing-box-revision", "unknown", "sing-box Git revision")
	flag.Parse()
	return config
}

func run(config options) error {
	if config.xrayPath == "" || config.singBoxPath == "" {
		return errors.New("both -xray and -sing-box are required")
	}

	workDir, err := os.MkdirTemp("", "native-naive-e2e-")
	if err != nil {
		return fmt.Errorf("create E2E work directory: %w", err)
	}
	defer os.RemoveAll(workDir)

	certificatePath, keyPath, caPEM, err := createCertificates(workDir)
	if err != nil {
		return err
	}

	tcpListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("start TCP echo server: %w", err)
	}
	defer tcpListener.Close()
	go serveTCPEcho(tcpListener)

	udpListener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		return fmt.Errorf("start UDP echo server: %w", err)
	}
	defer udpListener.Close()
	go serveUDPEcho(udpListener)

	naivePort, err := reservePort("tcp")
	if err != nil {
		return err
	}
	xrayTCPPort, err := reservePort("tcp")
	if err != nil {
		return err
	}
	xrayUDPPort, err := reservePort("udp")
	if err != nil {
		return err
	}

	singBoxConfigPath := filepath.Join(workDir, "sing-box.json")
	if err := writeJSON(singBoxConfigPath, singBoxConfig(naivePort, certificatePath, keyPath)); err != nil {
		return err
	}
	xrayConfigPath := filepath.Join(workDir, "xray.json")
	tcpEchoPort := tcpListener.Addr().(*net.TCPAddr).Port
	udpEchoPort := udpListener.LocalAddr().(*net.UDPAddr).Port
	if err := writeJSON(xrayConfigPath, xrayConfig(naivePort, xrayTCPPort, xrayUDPPort, tcpEchoPort, udpEchoPort, string(caPEM))); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	singBoxProcess, err := startProcess(ctx, workDir, "sing-box", config.singBoxPath, "run", "-c", singBoxConfigPath)
	if err != nil {
		return err
	}
	defer singBoxProcess.stop()
	if err := waitForTCP(naivePort, 15*time.Second); err != nil {
		return fmt.Errorf("sing-box Naive inbound did not start: %w\n%s", err, singBoxProcess.logs())
	}

	xrayProcess, err := startProcess(ctx, workDir, "xray", config.xrayPath, "run", "-c", xrayConfigPath)
	if err != nil {
		return err
	}
	defer xrayProcess.stop()
	if err := waitForTCP(xrayTCPPort, 20*time.Second); err != nil {
		return fmt.Errorf("Xray TCP inbound did not start: %w\nXray log:\n%s\nsing-box log:\n%s", err, xrayProcess.logs(), singBoxProcess.logs())
	}

	if err := testTCPRoundTrip(xrayTCPPort); err != nil {
		return fmt.Errorf("Naive TCP round trip failed: %w\nXray log:\n%s\nsing-box log:\n%s", err, xrayProcess.logs(), singBoxProcess.logs())
	}
	if err := testUDPRoundTrip(xrayUDPPort); err != nil {
		return fmt.Errorf("Naive UoT v2 round trip failed: %w\nXray log:\n%s\nsing-box log:\n%s", err, xrayProcess.logs(), singBoxProcess.logs())
	}

	result := testResult{
		Passed:            true,
		TCP:               "passed",
		UDPOverTCP:        "passed",
		UDPOverTCPVersion: 2,
		UDPVersionSource:  "defaulted by Xray because udpOverTcp.version was omitted",
		XrayRevision:      config.xrayRevision,
		SingBoxRevision:   config.singBoxRevision,
		CompletedAt:       time.Now().UTC().Format(time.RFC3339),
	}
	if err := writeJSON(config.outputPath, result); err != nil {
		return err
	}
	encoded, _ := json.Marshal(result)
	fmt.Println(string(encoded))
	return nil
}

func createCertificates(workDir string) (string, string, []byte, error) {
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", nil, fmt.Errorf("generate CA key: %w", err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Native Naive E2E CA"},
		NotBefore:             now.Add(-time.Minute),
		NotAfter:              now.Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		return "", "", nil, fmt.Errorf("create CA certificate: %w", err)
	}
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})

	serverKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", nil, fmt.Errorf("generate server key: %w", err)
	}
	serverTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: testServerName},
		DNSNames:     []string{testServerName},
		NotBefore:    now.Add(-time.Minute),
		NotAfter:     now.Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	serverDER, err := x509.CreateCertificate(rand.Reader, serverTemplate, caTemplate, &serverKey.PublicKey, caKey)
	if err != nil {
		return "", "", nil, fmt.Errorf("create server certificate: %w", err)
	}
	serverPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: serverDER})
	serverKeyDER, err := x509.MarshalPKCS8PrivateKey(serverKey)
	if err != nil {
		return "", "", nil, fmt.Errorf("encode server key: %w", err)
	}
	serverKeyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: serverKeyDER})

	certificatePath := filepath.Join(workDir, "server.pem")
	keyPath := filepath.Join(workDir, "server.key")
	if err := os.WriteFile(certificatePath, serverPEM, 0o600); err != nil {
		return "", "", nil, fmt.Errorf("write server certificate: %w", err)
	}
	if err := os.WriteFile(keyPath, serverKeyPEM, 0o600); err != nil {
		return "", "", nil, fmt.Errorf("write server key: %w", err)
	}
	return certificatePath, keyPath, caPEM, nil
}

func singBoxConfig(port int, certificatePath string, keyPath string) map[string]any {
	return map[string]any{
		"log": map[string]any{"level": "debug", "timestamp": true},
		"inbounds": []any{map[string]any{
			"type":        "naive",
			"tag":         "naive-in",
			"listen":      "127.0.0.1",
			"listen_port": port,
			"network":     "tcp",
			"users": []any{map[string]any{
				"username": testUsername,
				"password": testPassword,
			}},
			"tls": map[string]any{
				"enabled":          true,
				"server_name":      testServerName,
				"certificate_path": certificatePath,
				"key_path":         keyPath,
			},
		}},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route":     map[string]any{"final": "direct"},
	}
}

func xrayConfig(naivePort int, tcpInboundPort int, udpInboundPort int, tcpTargetPort int, udpTargetPort int, caPEM string) map[string]any {
	return map[string]any{
		"log": map[string]any{"loglevel": "debug"},
		"inbounds": []any{
			map[string]any{
				"listen":   "127.0.0.1",
				"port":     tcpInboundPort,
				"protocol": "dokodemo-door",
				"tag":      "tcp-in",
				"settings": map[string]any{
					"address": "127.0.0.1",
					"port":    tcpTargetPort,
					"network": "tcp",
				},
			},
			map[string]any{
				"listen":   "127.0.0.1",
				"port":     udpInboundPort,
				"protocol": "dokodemo-door",
				"tag":      "udp-in",
				"settings": map[string]any{
					"address": "127.0.0.1",
					"port":    udpTargetPort,
					"network": "udp",
				},
			},
		},
		"outbounds": []any{map[string]any{
			"protocol": "naive",
			"tag":      "naive-out",
			"settings": map[string]any{
				"address":             "127.0.0.1",
				"port":                naivePort,
				"username":            testUsername,
				"password":            testPassword,
				"insecureConcurrency": 1,
				"udpOverTcp":          map[string]any{"enabled": true},
				"tls": map[string]any{
					"serverName":  testServerName,
					"certificate": []string{caPEM},
				},
			},
			"mux": map[string]any{"enabled": false, "concurrency": -1},
		}},
		"routing": map[string]any{
			"rules": []any{map[string]any{
				"type":        "field",
				"inboundTag":  []string{"tcp-in", "udp-in"},
				"outboundTag": "naive-out",
			}},
		},
	}
}

func writeJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return fmt.Errorf("encode %s: %w", path, err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("create parent directory for %s: %w", path, err)
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o600); err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	return nil
}

func reservePort(network string) (int, error) {
	if network == "tcp" {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, fmt.Errorf("reserve TCP port: %w", err)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		if err := listener.Close(); err != nil {
			return 0, fmt.Errorf("release TCP port %d: %w", port, err)
		}
		return port, nil
	}

	listener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		return 0, fmt.Errorf("reserve UDP port: %w", err)
	}
	port := listener.LocalAddr().(*net.UDPAddr).Port
	if err := listener.Close(); err != nil {
		return 0, fmt.Errorf("release UDP port %d: %w", port, err)
	}
	return port, nil
}

func serveTCPEcho(listener net.Listener) {
	for {
		connection, err := listener.Accept()
		if err != nil {
			return
		}
		go handleTCPEcho(connection)
	}
}

func handleTCPEcho(connection net.Conn) {
	defer connection.Close()
	_, _ = io.Copy(connection, connection)
}

func serveUDPEcho(listener *net.UDPConn) {
	buffer := make([]byte, 64*1024)
	for {
		count, remoteAddress, err := listener.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		_, _ = listener.WriteToUDP(buffer[:count], remoteAddress)
	}
}

func startProcess(ctx context.Context, workDir string, name string, executable string, arguments ...string) (*managedProcess, error) {
	logPath := filepath.Join(workDir, name+".log")
	logFile, err := os.Create(logPath)
	if err != nil {
		return nil, fmt.Errorf("create %s log: %w", name, err)
	}
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Dir = filepath.Dir(executable)
	command.Stdout = logFile
	command.Stderr = logFile
	if err := command.Start(); err != nil {
		logFile.Close()
		return nil, fmt.Errorf("start %s: %w", name, err)
	}
	return &managedProcess{command: command, logFile: logFile, logPath: logPath}, nil
}

func (process *managedProcess) stop() {
	process.once.Do(func() {
		if process.command.Process != nil && process.command.ProcessState == nil {
			_ = process.command.Process.Kill()
		}
		_ = process.command.Wait()
		_ = process.logFile.Close()
	})
}

func (process *managedProcess) logs() string {
	data, err := os.ReadFile(process.logPath)
	if err != nil {
		return fmt.Sprintf("read process log: %v", err)
	}
	return string(data)
}

func waitForTCP(port int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	for time.Now().Before(deadline) {
		connection, err := net.DialTimeout("tcp", address, 300*time.Millisecond)
		if err == nil {
			connection.Close()
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("timed out waiting for %s", address)
}

func testTCPRoundTrip(port int) error {
	payload := []byte("native-naive-tcp")
	connection, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 5*time.Second)
	if err != nil {
		return err
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	if _, err := connection.Write(payload); err != nil {
		return err
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(connection, response); err != nil {
		return err
	}
	if !bytes.Equal(payload, response) {
		return fmt.Errorf("unexpected response %q", response)
	}
	return nil
}

func testUDPRoundTrip(port int) error {
	payload := []byte("native-naive-uot-v2")
	connection, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port})
	if err != nil {
		return err
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return err
	}
	if _, err := connection.Write(payload); err != nil {
		return err
	}
	response := make([]byte, 64*1024)
	count, err := connection.Read(response)
	if err != nil {
		return err
	}
	if !bytes.Equal(payload, response[:count]) {
		return fmt.Errorf("unexpected response %q", response[:count])
	}
	return nil
}
