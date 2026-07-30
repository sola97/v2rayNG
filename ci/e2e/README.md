# Native Naive interoperability test

`invoke-naive-e2e.ps1` builds pinned Xray and sing-box revisions, places the
pinned Windows Cronet runtime beside Xray, and runs a real Naive connection
between them.

The test covers both main data paths:

- TCP through Xray's native `protocol: "naive"` outbound to a sing-box Naive
  inbound and a local TCP echo server.
- UDP through UoT to a local UDP echo server. The generated Xray configuration
  deliberately omits `udpOverTcp.version`, so this exercises the core's default
  UoT v2 behavior.

It does not start `naiveplugin`, a local SOCKS bridge, or a second client core.
sing-box is only the independent test server.

Run from the repository root on Windows amd64:

```powershell
.\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot E:\CodexBuildCache\native-naive
```

For existing clean, pinned source checkouts, pass `-XraySource` and
`-SingBoxSource`. Otherwise the runner clones the fixed public repositories and
checks out the requested immutable revisions. The output directory contains
`naive-e2e-result.json` and a manifest with source revisions and binary SHA-256
hashes.
