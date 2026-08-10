# Releasing to Maven Central

## Release Gate for 1.0.0
The API contract is locked (see README "Stability"), but 1.0.0 does not publish until it has survived contact with real applications: flamelens is deployed and live; the launch blog post is written using flamelens's analysis of the JFR recordings in jfr/; warehouseorganizer AND tüük both run against fusio in real use with no interop issues (WHO already runs it for all CSV paths; tüük's RestClient-based WhoClient exercises the starter's client side). Until then the version stays 1.0.0-SNAPSHOT, consumed from the local .m2.

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
mvn versions:set -DnewVersion=1.1.0 -DprocessAllModules=true
mvn versions:set-property -Dproperty=revision -DnewVersion=1.1.0
mvn versions:commit
```

### 2. Execute a Deep Verification Build
Verify code integrity and run the full suite of JDBC integration tests against your local real-world staging databases:
```bash
docker compose up -d --wait
mvn clean verify
mvn test -pl fusio-jdbc -Dtest=MySqlLoadDataIT,PostgresCopyIT
```

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
git tag v1.1.0
git push origin --tags

# Roll over to the next micro patch cycle snapshot
mvn versions:set -DnewVersion=1.1.1-SNAPSHOT -DprocessAllModules=true
mvn versions:commit
git commit -am "Bump development cycle version to 1.1.1-SNAPSHOT"
git push origin main
```
