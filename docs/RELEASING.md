# Releasing to Maven Central

## Release gate for 1.0.0

The API contract is locked (see README "Stability"), but 1.0.0 does not
publish until it has survived contact with real applications:

1. flamelens is deployed and live;
2. the launch blog post is written using flamelens's analysis of the JFR
   recordings in `jfr/`;
3. warehouseorganizer AND tüük both run against fusio in real use with no
   interop issues (WHO already runs it for all CSV paths; tüük's
   RestClient-based WhoClient exercises the starter's client side).

Until then the version stays 1.0.0-SNAPSHOT, consumed from the local .m2.

## One-time setup (manual, account-owner steps):

1. **Central account** — sign in at https://central.sonatype.com (GitHub SSO
   works). Generate a user token (Account → Generate User Token) and put it
   in `~/.m2/settings.xml`:
   ```xml
   <servers>
     <server>
       <id>central</id>
       <username>TOKEN_USERNAME</username>
       <password>TOKEN_PASSWORD</password>
     </server>
   </servers>
   ```
2. **Namespace** — register `dev.flamelens` (Namespaces → Add). Central
   issues a verification code; add it as a DNS TXT record on
   `flamelens.dev` at the registrar, then click Verify. (This is why the
   groupId is dev.flamelens: we own that domain.)
3. **GPG key** — `gpg --gen-key` (use the release email), then publish:
   `gpg --keyserver keys.openpgp.org --send-keys <KEYID>`.

Per release:

1. Set the version: `mvn versions:set -DnewVersion=1.0.0` (drop -SNAPSHOT).
2. Full build: `mvn clean verify` — plus the JDBC integration tests against
   real databases (`docker compose up -d --wait`, see compose.yaml): `mvn test -pl fusio-jdbc -Dtest=MySqlLoadDataIT,PostgresCopyIT`.
3. Publish: `mvn -Prelease clean deploy` — attaches sources + javadoc,
   signs, and uploads via the central-publishing plugin (fusio-bench is
   excluded automatically). Review and publish the deployment in the
   Central portal UI (autoPublish is off on purpose).
4. Tag and bump: `git tag v1.0.0 && git push --tags`, then
   `mvn versions:set -DnewVersion=1.1.0-SNAPSHOT` and commit.

After the first release: switch warehouseorganizer's fusio dependencies
from 1.0.0-SNAPSHOT (local .m2) to 1.0.0 (Central) — that unblocks its
Heroku builds.

GitHub note: the repo lives at tim-warehouseorganizer/fusio because the
`flamelens` GitHub name is held by an unrelated party (Flamelens Media);
`flamelens-dev` (matching flamelens.dev) is available as an org — creating
it and transferring this repo is a web-UI operation, and GitHub redirects
the old URL after transfer.
