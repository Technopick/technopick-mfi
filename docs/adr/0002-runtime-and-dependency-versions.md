# ADR-0002: Runtime and dependency versions

- Status: Accepted, versions to re-confirm on first networked build
- Date: 2026-11-18

## Decision

| Component | Version | Notes |
| --- | --- | --- |
| Java | 25 LTS | Next LTS is 29 (Sep 2027). A `jdk21-compat` Maven profile exists only so pure-Java modules can be verified on JDK 21 machines |
| Spring Boot | 4.1.x (4.1.1 pinned) | 3.5 OSS support ended 2026-06-30. Move to 4.2 when GA and validated |
| Apache Camel | 4.22.x LTS (4.22.2 pinned) | LTS, Java 17/21/25, EOL Aug 2027; next LTS expected ~Feb 2027 |
| jPOS | 3.0.2 | Released 2026-09-30; raised Java baseline; structured logging changes. Pin exact version; jPOS is AGPLv3 or commercial licence - **licence decision required** before distribution |
| PostgreSQL | 16 minimum, 17 recommended | RLS, deferrable constraint triggers, btree_gist |
| JUnit / AssertJ / jqwik / ArchUnit / Testcontainers | as in root `pom.xml` | Versions written without registry access - verify and bump on first networked build |

## Rules
- All versions live only in the root `pom.xml`; modules never declare versions.
- Dependency updates go through CI with SBOM + CVE scan; no SNAPSHOTs in release builds
  (jPOS recommends pinned timestamped snapshots for production - we pin releases instead).

## Open item
jPOS licensing: AGPLv3 obligations for a SaaS switch must be reviewed by counsel, or a
commercial jPOS licence purchased.
