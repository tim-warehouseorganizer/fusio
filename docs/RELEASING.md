# Releasing to Maven Central

Published artifacts: `fusio-core`, `fusio-jdbc`, `fusio-ffm`, `fusio-spring-boot-starter`,
`fusio-spring-boot3-starter`, `fusio-spring-boot2-starter` (`fusio-bench` skips publishing).
Every published module needs its own `<url>` and `<scm><url>` — Central rejects the bundle
otherwise. Java baselines per module are listed in the README; the release build itself
always runs on JDK 25 (`--release` handles the bytecode level; javadoc/gpg/central plugins
need a modern JDK).

## Release Gate
A release publishes only after all three hold:

1. **The `CI` workflow is green on the exact release commit.** That matrix is what proves the
   baselines: each job builds on JDK 25 and then runs the suites on a real Temurin 8, 11, 17,
   21 or 25 via surefire's `-Djvm`, plus a Boot 3.2–3.5 sweep. Signatures compiling is not proof.
2. **`MySqlLoadDataIT` and `PostgresCopyIT` pass** against the `compose.yaml` databases (they
   `assumeTrue` on a reachable connection — check for `Skipped: 0`, not just a green build).
3. **At least one real consumer builds and boots** against the staged version: warehouseorganizer
   for core/jdbc/starter, flamelens or plainsight for core alone.

1.0.0 carried an additional one-time gate — real-application interop before any Central
publish at all. That is met: 1.0.0 and 1.1.1 are live on Central.

---

## Understanding Snapshots vs. Releases

To avoid broken metadata or build failures, it is critical to understand the distinction between development builds and public releases:

### 1. SNAPSHOT Versions (Development / Inner Loop)
* **What they are:** Active, mutable development versions (e.g., `1.1.0-SNAPSHOT`).
* **Where they go:** Pushed to your Git branches and deployed automatically to **GitHub Packages** via the `Publish to GitHub Packages` GitHub Actions workflow.
* **How they behave:** Maven treats snapshots as "moving targets." Every time you push code, your consumer projects (`warehouseorganizer`, `tüük`) will dynamically fetch the newest build from GitHub Packages without needing a version bump.
* **Central Policy:** **Maven Central strictly forbids `-SNAPSHOT` versions.** They cannot be uploaded there.

### 2. Standard Releases (Public / Immutable)
* **What they are:** Static, unchangeable versions (e.g., `1.1.0`, `1.1.1`).
* **Where they go:** Uploaded strictly to **Maven Central** (Sonatype Portal) via local terminal execution.
* **How they behave:** Once a release version hits Maven Central, it is **frozen forever**. You cannot alter or overwrite its code, POM metadata, or child URLs. If a mistake is found (like a 404 URL), you must cut a brand-new, incremented release version (e.g., `1.1.1`).

---

## One-Time Setup (Manual, Account-Owner Steps)

1. **Central Account** — Sign in at https://sonatype.com (GitHub SSO works). Generate a user token (**Account → Generate User Token**) and put it in `~/.m2/settings.xml`:
   ```xml
   <servers>
     <server>
       <id>central</id>
       <username>TOKEN_USERNAME</username>
       <password>TOKEN_PASSWORD</password>
     </server>
   </servers>
   ```
2. **Namespace** — Register `dev.flamelens` (**Namespaces → Add**). Central issues a verification code; add it as a DNS TXT record on `flamelens.dev` at the registrar, then click Verify.
3. **GPG Key** — Run `gpg --gen-key` (use the release email), then publish it:
   ```bash
   gpg --keyserver keys.openpgp.org --send-keys <KEYID>
   ```

---

## Per Release Execution Workflow

Follow these steps exactly when moving from a development cycle to a live public release:

### 1. Update the Local Version Structure
Strip the snapshot suffix across the parent project and all child submodules simultaneously:
```bash
mvn versions:set -DnewVersion=2.0.0 -DprocessAllModules=true
mvn versions:set-property -Dproperty=revision -DnewVersion=2.0.0
mvn versions:commit
```

### 2. Execute a Deep Verification Build
Verify code integrity and run the full suite of JDBC integration tests against your local real-world staging databases:
```bash
docker compose up -d --wait
mvn clean verify
mvn test -pl fusio-jdbc -Dtest=MySqlLoadDataIT,PostgresCopyIT
```

Then prove the Java 8 baseline on a real Java 8 runtime (the build JDK stays 25;
only the forked test JVM changes). Point `-Djvm` at a Temurin 8 `java` binary:
```bash
mvn -pl fusio-core,fusio-jdbc,fusio-spring-boot2-starter surefire:test -Djvm=/path/to/jdk8/bin/java
```
CI runs the same on 8/11/17/21/25 plus a Boot 3.2–3.5 sweep; a green `CI`
workflow on the release commit is the gate.

### 3. Deploy and Sign (Using Terminal Loopback)
To prevent terminal hang-ups where the background GPG agent fails to render a UI password window, pass explicit loopback flags. This forces Maven to capture your PGP passphrase directly in your active terminal thread:
```bash
mvn -Prelease clean deploy -Dgpg.passphraseServerId=central -Darguments="-Dgpg.loopback=true"
```

### 4. Finalize via the Sonatype Web UI
* Log into the [Sonatype Central Portal](https://sonatype.com/).
* Navigate to the **Deployments** tab.
* Wait for the automatic layout and signature verifications to clear.
* Click **Publish** (Note: `autoPublish` is disabled intentionally to allow a final manual gate check).
* *Note: The status will say "Publishing" for 15–30 minutes while syncs to the global CDN take place. Real-time portal text search can take 2–4 hours to index the new version.*

### 5. Tag and Reset the Development Loop
Once verified, lock the tag into your remote repository and immediately shift your local workspace back into active Snapshot development tracking:
```bash
git tag v2.0.0
git push origin --tags

# Roll over to the next micro patch cycle snapshot
mvn versions:set -DnewVersion=2.0.1-SNAPSHOT -DprocessAllModules=true
mvn versions:commit
git commit -am "Bump development cycle version to 2.0.1-SNAPSHOT"
git push origin main
```
