# CRM — TAGTOG local login prototype

This branch adds a local login bridge to the existing CRM user model. It does not implement account packages, personal/company workspaces, or cross-company data isolation. Use isolated fixture data only until those policies are implemented and reviewed.

## Services

- Portal: `http://127.0.0.1:4180`
- IAM: `http://127.0.0.1:8181`
- CRM API: `http://127.0.0.1:8186`
- CRM frontend: `http://127.0.0.1:8188`
- IAM client: `crm-local`, system: `CRM`
- Exact callback: `http://127.0.0.1:8188/api/tagtog/callback`

CRM has its own IAM client secret, separate from PMS. Codes, PKCE transactions, and central sessions must be bound to that client. The browser never receives IAM tokens or the client secret.

## Run safely

Build with Java 17+ and Maven:

```powershell
mvn -Ddependency-check.skip=true package
```

Run only with the explicit local profile and generated external environment values:

```powershell
java -jar target/crm-kimtele-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=tagtog-local
```

Required: `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `TAGTOG_SSO_CLIENT_SECRET`. Optional service configuration: `TAGTOG_IAM_BASE_URL`, `TAGTOG_IAM_ISSUER`, `TAGTOG_SSO_CLIENT_ID`, and `TAGTOG_SSO_REDIRECT_URI`.

The local profile binds API port 8186 to loopback, uses PostgreSQL at `127.0.0.1:55433/tagtog_crm`, disables application/bootstrap seeds and EMS network integration, and uses a local changelog that excludes the legacy fixed-admin seed. It does not connect to the default database profile. Keep generated credentials outside Git. The local changelog includes the current EMS source column; disabled EMS integration rejects both legacy and v2 reads/sync before network or CRM access.

Do not run the default application profile for this preview: the existing default selects PostgreSQL and enables seed settings for the older CRM environment.

## API contract

`POST /api/tagtog/exchange` accepts JSON `{code, codeVerifier, redirectUri}`. It exchanges the one-use IAM code, verifies current IAM access, resolves the explicit identity link, and returns the existing CRM login shape:

```json
{
  "token": "opaque CRM session token",
  "expiresAt": "ISO UTC timestamp",
  "userId": 17,
  "username": "existing-crm-user",
  "email": "fixture@example.test",
  "fullName": "Local Fixture",
  "roles": ["MANAGER"],
  "allowedEventIds": [88]
}
```

The CRM frontend BFF stores the opaque token on its server and gives the browser an HttpOnly cookie. The API stores only a SHA-256 hash of the 256-bit random token. Numeric session IDs, unbound sessions, expired sessions, and revoked/mismatched mappings are rejected when SSO is enabled. No user is automatically created, linked by email, or promoted.

`GET /api/auth/me` uses the opaque Authorization token and returns a safe DTO with `id`, `username`, `email`, `fullName`, `roles`, and `allowedEventIds`. Password hashes and IAM bindings are not serialized.

`POST /api/auth/logout` accepts the opaque token in the Authorization header only in SSO mode. It revokes that local CRM session even during an IAM outage. It does not sign out other applications. TAGTOG global sign-out revokes central SSO sessions; CRM checks that revocation on subsequent protected requests.

## Explicit account mapping

The following routes require live IAM `SUPER_ADMIN` plus current CRM system access. They do not accept a local CRM admin role as a substitute:

- `GET /api/tagtog/admin/users`
- `GET /api/tagtog/admin/links`
- `PUT /api/tagtog/admin/links/{subject}` with `{crmUserId: number}`
- `DELETE /api/tagtog/admin/links/{subject}`

Links are unique by `(issuer, subject)` and by existing CRM user ID. Creating/changing a link requires an active existing IAM identity and a CRM user with explicitly assigned local roles. Mapping changes revoke existing CRM sessions and rotate a link version, preventing an in-flight session from surviving a remap back to the same local user. Existing CRM IDs, roles, event assignments, and ownership stay in place.

## Request authorization

With `TAGTOG_SSO_ENABLED=true`, a central filter protects `/api` routes, including legacy endpoints that previously omitted authentication. Each protected request checks current IAM status, CRM system access, `CRM/workspace` CRUD grants, the current identity mapping, and local session expiry. Existing CRM role/event restrictions still apply after these checks. IAM outages fail closed with 503.

Read operations require view and DELETE requires delete. The action map also accounts for side effects that do not match the HTTP verb:

- Read-only import validation/preview and contact-merge preview require view only.
- Legacy flag list reads require edit plus delete because they can link flags and deactivate contacts. Removing a flag also requires edit plus delete because it can reactivate contacts and update participant statuses.
- Known POST creates require create; imports/EMS sync require create plus edit; assignment auto-split requires edit.
- Cleaner apply/group merge and contact merge require edit plus delete because they delete source records.
- Creating removal requests or flags requires create, edit, and delete because it can immediately archive an existing contact.
- Updating a contact, flag, or removal status requires edit plus delete because the accepted body/status can archive or restore contacts. This prototype conservatively caps the whole endpoint rather than interpreting individual fields.
- Unknown POST operations require create, edit, and delete until their side effects have been audited.

All actions also require view, and existing local CRM roles still apply. These checks are workspace CRUD caps; they do not claim a complete product feature-permission model. Review the per-route action map before enabling finer permission combinations.

Local password login, registration/bootstrap, and CRM password reset are disabled in SSO mode. Setting SSO false leaves the legacy password login behavior available; this prototype does not claim that the old numeric-session mechanism is suitable for production.

Narrow exemptions:

- Code exchange, with PKCE/client/callback validation in IAM and CRM.
- Admin mapping routes, with their own live central-admin check.
- Local CRM sign-out, which only removes the caller's opaque local session.
- The existing GET email tracking pixel `/api/event-participants/emails/track/{activityId}`, used by external email readers. It returns a pixel rather than CRM records. Its legacy predictable activity ID still needs replacement with a signed/random capability before a production security rollout.
- CORS OPTIONS preflight, which does not execute business handlers.

## Migration and tests

The additive Liquibase changes create identity links and extend `session_tokens` with hashed opaque tokens, IAM binding, revocation, and mapping version. Existing numeric sessions are not silently converted into SSO sessions. A second migration revokes any earlier prototype sessions lacking a mapping version.

```powershell
mvn -Dtest=Tagtog*Test test
```

Tests use mocked repositories, standalone MockMvc, and a loopback mock IAM HTTP server; they do not start Spring or open a database. The Maven Surefire version is pinned for JUnit 5 discovery. Building a JAR and passing isolated tests do not by themselves establish a successful browser-to-CRM end-to-end run; runtime verification is performed separately against the dedicated local database.
