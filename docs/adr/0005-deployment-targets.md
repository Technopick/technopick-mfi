# ADR-0005: Deployment targets - Linux containers and Windows Server services

- Status: Accepted
- Date: 2026-11-18

## Decision
- Same JARs for both targets; only packaging/installation differs.
- Linux (default for SaaS): OCI images, Kubernetes or VMs.
- Windows Server 2025 LTSC (dedicated/on-prem): each JAR is a Windows Service via WinSW,
  running under a gMSA or dedicated least-privilege account, delayed auto-start, restart on
  failure, graceful stop. Templates: `infrastructure/deployment/windows-server/`.
- PostgreSQL and Redis on Linux hosts are recommended even for Windows deployments.
- Switch TCP/mTLS terminates in the switch (or a TLS proxy), never in IIS.
- CI runs on `ubuntu-latest` and `windows-latest`; a pass on one OS is not evidence for the other.

## Platform-neutral code rules (enforced in review; ArchUnit rules to follow)
`java.nio.file.Path` only, explicit UTF-8, no shell-outs, `java.time` with configured zones,
Camel file routes with explicit read locks.
