PMS Backend (Spring Boot)

Overview
- Java Spring Boot 3 backend for the PMS frontend (`pms_fe`).
- Provides REST APIs for Projects, Workflow Stages, Events, Documents, Contacts, and Team Members.
- Uses H2 in-memory database with optional seed data aligned to the UI.

Quick Start
1. Build the fat JAR:
   - `mvn -DskipTests package spring-boot:repackage`
2. Run the app:
   - `java -jar target/pms-be-0.0.1-SNAPSHOT.jar`
3. Visit endpoints:
   - `GET http://localhost:8080/api/projects`
   - `GET http://localhost:8080/api/projects/{id}`
   - `GET http://localhost:8080/api/projects/{id}/stages|events|documents|contacts|team-members`
   - `POST http://localhost:8080/api/projects` (and similar nested POSTs to add items)
   - `GET http://localhost:8080/api/team-members`
  - `GET http://localhost:8080/api/frontend/projects` (frontend-friendly simplified list)
   - CRUD endpoints for other resources:
     - Stages: `GET/POST /api/stages`, `GET/PUT/DELETE /api/stages/{id}` (POST supports `?projectId=`)
     - Events: `GET/POST /api/events`, `GET/PUT/DELETE /api/events/{id}` (POST supports `?projectId=`)
     - Documents: `GET/POST /api/documents`, `GET/PUT/DELETE /api/documents/{id}` (POST supports `?projectId=`)
     - Contacts: `GET/POST /api/contacts`, `GET/PUT/DELETE /api/contacts/{id}` (POST supports `?projectId=`)
     - Team Members: `GET/POST /api/team-members`, `GET/PUT/DELETE /api/team-members/{id}`

Tech
- Java 17 source, Spring Boot 3.3.x, Spring Data JPA, Bean Validation
- H2 database (`/h2-console`), CORS enabled for `http://localhost:3000`
 - Optional MySQL profile available (see below)

Config
- `src/main/resources/application.properties`
  - `server.port=8080`
  - `spring.jpa.hibernate.ddl-auto=create`
  - `spring.h2.console.enabled=true`
  - `app.cors.allowed-origins=http://localhost:3000`
  - `app.data.seed=true` (toggle demo data seeding)

Data Seeding Toggle
- The demo data seeder (`DataInitializer`) is enabled by default via `app.data.seed=true`.
- Disable in staging/production by setting `app.data.seed=false` (env var `APP_DATA_SEED=false`, JVM `-Dapp.data.seed=false`, or profile file).
- A `application-staging.properties` is provided with `app.data.seed=false`. Run with:
  - `mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=staging"`
  - or `java -jar target/pms-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=staging`

MySQL Profile
- Use local MySQL with default credentials (root / no password):
  - `src/main/resources/application-mysql.properties`
    - `spring.datasource.url=jdbc:mysql://localhost:3306/pms?createDatabaseIfNotExist=true...`
    - `spring.datasource.username=root`
    - `spring.datasource.password=`
    - `spring.jpa.hibernate.ddl-auto=update`
- Run with MySQL profile:
  - `mvn spring-boot:run -Dspring-boot.run.profiles=mysql`
  - or `java -jar target/pms-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql`

Notes
- Entities are exposed with nested relationships for convenience. If you want DTOs instead of entities on the wire or to adjust relationships, I can refactor accordingly.
- Security is not configured; add Spring Security/JWT if auth is needed.

Profiles
- dev (default): H2 in-memory, Liquibase disabled (fast local iteration)
  - Run: `mvn spring-boot:run`
  - DB: H2 in-memory; H2 console at `/h2-console`
- postgres (local): PostgreSQL via Docker, Liquibase enabled
  - Start DB: `docker compose up -d` (from `pms_be/`)
  - Run: `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`
  - Config file: `src/main/resources/application-postgres.properties`
- test: PostgreSQL (Liquibase ON) targeting `pms_test`
  - Run: `mvn spring-boot:run -Dspring-boot.run.profiles=test`
  - Config file: `src/main/resources/application-test.properties`
- prod: PostgreSQL (Liquibase ON; env-driven)
  - Run: `java -jar target/pms-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod`
  - Config file: `src/main/resources/application-prod.properties`

Database Configuration
- Postgres properties (override via env):
  - `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
  - Or a single `JDBC_URL`, e.g. `jdbc:postgresql://localhost:5432/pms_dev`
- Liquibase
  - Enabled in `postgres`, `test`, and `prod` profiles; disabled in `dev`
  - Master changelog: `classpath:db/changelog/db.changelog-master.yaml` (includes weekly `changes/changelog-wc-42.yml`)
- Docker Compose
  - File: `pms_be/docker-compose.yml` with `.env` in the same folder
  - Bring up Postgres: `docker compose up -d`

IntelliJ IDEA Tips
- Set Active Profile(s): Run/Debug Configurations → VM Options `-Dspring.profiles.active=postgres` (or `dev`, `test`, `prod`)
- Maven JDK: use Java 17 or 21
- Annotation Processing: enable for Lombok

Liquibase CLI vs Spring Boot
- A `liquibase.properties` file is only used by Liquibase CLI/Maven plugin.
- Spring Boot uses `application-*.properties` for datasource and Liquibase config; CLI properties don’t affect Boot unless you run the plugin directly.

Seeded Admin (Postgres profile)
- username: `admin`
- email: `admin@example.com`
- password: `admin`
- roles: `ADMIN`, active: `true`, approved: `true`

Frontend Integration
- The endpoint `GET /api/frontend/projects` returns a simplified list shaped for the Next.js UI:
  - `{ id, name, client, eventDate, status, progress, daysUntilEvent, currentStage, priority }`
- CORS is enabled for `http://localhost:3000` by default (see `app.cors.allowed-origins`).

## Merge kontak database (ADMIN only, PostgreSQL)

Endpoint ini menggabungkan **satu pasangan kontak** per transaksi, bukan merge company/group.
Deploy backend beserta migration `changelog-crm-database-merge-audits.yml` sebelum digunakan.
Tidak ada data production yang otomatis berubah ketika migration dijalankan.

1. `POST /api/databases/merge/preview` dengan header `Authorization: Bearer <token ADMIN>` dan `Content-Type: application/json`:

   ```json
   {
     "targetId": 140,
     "sourceId": 2422,
     "fieldChoices": {},
     "emailTypes": {"jodianto@gmail.com": "personal"}
   }
   ```

2. Preview mengembalikan `conflicts`, calon `contact`/`emails`, hitungan relasi, dan `previewToken`.
   ID target dipertahankan; ID source dihapus **hanya saat commit merge**.
   Field kosong/unknown diisi otomatis. Untuk dua nilai berbeda, pilih `"target"` atau `"source"` pada `fieldChoices` lalu preview ulang.
   Nama field menggunakan nama kolom: `first_name`, `last_name`, `company_id`, `salutation`, `position_level`,
   `speciality_division`, `job_title`, `mobile_phone`, `linkedin_url`, `database_type`, `source`.
   Contoh: `"fieldChoices": {"first_name":"target", "last_name":"target"}`.
3. Setelah `conflicts` kosong, kirim body yang **sama persis** ditambah `previewToken` ke `POST /api/databases/merge`.
   Request tanpa preview ditolak. Data/relasi berubah atau pilihan diganti setelah preview: `409`, wajib preview ulang.
   Retry token merge yang sudah sukses mengembalikan hasil sebelumnya tanpa menulis lagi.

Perilaku penting:

- Nama harus cocok setelah normalisasi, dengan bukti HP/email pribadi/LinkedIn individu yang sama. Nomor kantor sama saja tidak cukup.
- Company beda hanya diizinkan jika normalisasi nama company sama; pilihan company survivor tetap eksplisit.
- Email legacy dipisah per alamat, gabungan alamat dideduplikasi, tipe personal menang jika salah satu sudah personal.
  Domain tidak dipakai untuk menebak tipe: gunakan `emailTypes` untuk salah label legacy. Email pribadi yang juga ada pada kontak ketiga ditolak.
- Riwayat event dan activity mempertahankan ID; relasi flag dan removal request dipindah ke target.
  Jika kedua kontak ada pada event yang sama, merge **ditolak**, bukan menimpa salah satu registrasi.
- Tidak mengaktifkan ulang kontak nonaktif, confirmed flag, atau opt-out yang belum rejected.
- `created_by_user_id`, `entry_method`, `created_at` target tidak diubah. Source dengan creator berbeda/target tanpa creator ditolak;
  pilih record berkredit sebagai target. Dua record dengan creator yang sama tetap menjadi satu input setelah merge.
- Snapshot sebelum merge (termasuk relasi dan nilai yang tidak dipilih) disimpan di `database_merge_audits` dalam transaksi yang sama.
  Tabel berisi data pribadi, tidak diekspos ke endpoint log umum, dan akses SQL/retensinya harus dibatasi sesuai kebijakan backup.
  Ini backup untuk pemulihan manual, bukan endpoint undo otomatis.
- Semua langkah rollback jika gagal. Tabel terkait dikunci singkat untuk melindungi dari writer legacy; lakukan saat trafik tulis rendah.
  Lock timeout 5 detik; transaksi timeout 30 detik. Relasi FK baru yang belum didukung ditolak.

Pengujian PostgreSQL terisolasi (bukan database CRM):

```powershell
docker run --detach --rm --name crm-contact-merge-test -e POSTGRES_USER=merge_test -e POSTGRES_PASSWORD=merge_test_local_only -e POSTGRES_DB=merge_test -p 127.0.0.1:55439:5432 postgres:17-alpine
$env:CRM_MERGE_TEST_URL='jdbc:postgresql://127.0.0.1:55439/merge_test'
mvn test-compile org.apache.maven.plugins:maven-surefire-plugin:3.2.2:test
docker stop crm-contact-merge-test
```

Integration test hanya aktif jika URL menunjuk localhost dengan nama DB `merge_test`; schema `merge_test_case` di DB tes tersebut dibuat ulang tiap test.
