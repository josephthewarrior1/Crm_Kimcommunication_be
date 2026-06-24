# Database Design — Lead & Event Management System

Dokumen ini menjelaskan struktur database untuk sistem manajemen lead, company, contact person, event, status lead, removal request, dan deteksi suspicious identity atau "tikus".

> **Database:** PostgreSQL

---

## 1. Tujuan Database

Database ini dibuat untuk mengelola:

- Data holding / parent company
- Data company
- Data contact person
- Email company dan personal
- Event partner dan end-user
- Status lead per event
- History pemakaian lead di banyak event
- Request penghapusan data / takeout
- Validasi email personal vs corporate
- Deteksi duplicate / suspicious identity / "tikus"

---

## 2. Prinsip Utama Struktur Database

Data tidak disimpan dalam satu tabel besar seperti Excel.

**Struktur utama:**
```
groups
 ↓
companies
 ↓
contacts
 ↓
contact_emails
```

**Untuk event:**
```
events
 ↓
event_leads
 ↓
contacts
```

> Status warna lead disimpan di `event_leads`, **bukan** di `contacts`, karena status lead bersifat **per event**.

**Contoh:**

| Contact | Event | Status |
|---------|-------|--------|
| Contact A | Event 1 | green |
| Contact A | Event 2 | white |
| Contact A | Event 3 | red |

---

## 3. Core Tables

### 3.1 `groups`

Tabel untuk menyimpan holding atau parent company.

**Contoh:** Astra Group, Sinar Mas Land, Kompas Gramedia Group

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `name` | VARCHAR | Nama group / holding |
| `notes` | TEXT | Catatan tambahan |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Relationship:** `groups.id → companies.group_id`
Satu group bisa memiliki banyak company.

---

### 3.2 `companies`

Tabel untuk menyimpan data perusahaan.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `group_id` | FK | Relasi ke `groups.id`, nullable |
| `brand_name` | VARCHAR | Nama brand |
| `name` | VARCHAR | Nama legal company |
| `address` | TEXT | Alamat company |
| `office_phone` | VARCHAR | Nomor telepon kantor |
| `website` | VARCHAR | Website company |
| `industry` | VARCHAR | Industri company |
| `company_size_revenue` | VARCHAR | Size berdasarkan revenue |
| `company_size_employee` | VARCHAR | Size berdasarkan jumlah employee |
| `company_hardware` | TEXT | Info hardware company |
| `city` | VARCHAR | Kota |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Relationship:**
- `companies.group_id → groups.id`
- `companies.id → contacts.company_id`

Satu company bisa memiliki banyak contact.

---

### 3.3 `contacts`

Tabel untuk menyimpan data orang / contact person.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `company_id` | FK | Relasi ke `companies.id` |
| `salutation` | VARCHAR | Mr, Mrs, Ms, Dr, dll |
| `first_name` | VARCHAR | Nama depan |
| `last_name` | VARCHAR | Nama belakang |
| `position_level` | VARCHAR | Level jabatan |
| `speciality_division` | VARCHAR | Divisi / spesialisasi |
| `job_title` | VARCHAR | Jabatan lengkap |
| `mobile_phone` | VARCHAR | Nomor HP |
| `normalized_phone` | VARCHAR | Nomor HP format standar |
| `linkedin_url` | TEXT | Link LinkedIn |
| `contact_type` | ENUM / VARCHAR | Tipe kontak |
| `source` | ENUM / VARCHAR | Sumber data |
| `is_active` | BOOLEAN | Soft delete flag |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Contact Type** (nilai yang direkomendasikan):
- `partner_it`
- `partner_marketing`
- `end_user`
- `unknown`

**Source** (nilai yang direkomendasikan):
- `contactout`
- `old_db`
- `manual`
- `excel_import`
- `event_registration`

> **Notes:** `is_active = false` digunakan ketika contact sudah resign, pensiun, meninggal, atau meminta datanya dihapus. Data tidak langsung dihapus permanen agar history event tetap aman.

---

### 3.4 `contact_emails`

Tabel untuk menyimpan email contact.

**Alasan dibuat tabel terpisah:**
- Satu contact bisa punya lebih dari satu email
- Bisa membedakan email company dan personal
- Bisa dipakai untuk validasi corporate email
- Bisa dipakai untuk deteksi duplicate / suspicious identity

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `contact_id` | FK | Relasi ke `contacts.id` |
| `email` | VARCHAR | Email address |
| `email_type` | ENUM / VARCHAR | Jenis email |
| `is_primary` | BOOLEAN | Email utama atau bukan |
| `is_verified` | BOOLEAN | Email sudah diverifikasi atau belum |
| `is_corporate` | BOOLEAN | Email corporate atau personal |
| `domain` | VARCHAR | Domain email |
| `created_at` | TIMESTAMP | Waktu data dibuat |

**Email Type:** `company` · `personal` · `other`

**Contoh:**
- `nama@company.co.id` → `company`, `is_corporate = true`
- `nama@gmail.com` → `personal`, `is_corporate = false`

---

### 3.5 `events`

Tabel untuk menyimpan data event atau project.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `name` | VARCHAR | Nama event |
| `event_type` | ENUM / VARCHAR | Tipe event |
| `client_name` | VARCHAR | Nama client / requester |
| `date_start` | DATE | Tanggal mulai |
| `date_end` | DATE | Tanggal selesai |
| `notes` | TEXT | Catatan event |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Event Type:** `partner` · `end_user` · `internal` · `other`

---

### 3.6 `event_leads`

Tabel penghubung antara event dan contact. **Ini tabel paling penting untuk tracking status lead per event.**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `event_id` | FK | Relasi ke `events.id` |
| `contact_id` | FK | Relasi ke `contacts.id` |
| `lead_status` | ENUM / VARCHAR | Status warna lead |
| `requested_at` | TIMESTAMP | Kapan lead mulai dihubungi |
| `responded_at` | TIMESTAMP | Kapan lead merespon |
| `attendance_status` | ENUM / VARCHAR | Status kehadiran |
| `notes` | TEXT | Catatan per lead per event |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Lead Status:**

| Status | Arti |
|--------|------|
| `white` | Belum jawab |
| `yellow` | Tentative |
| `green` | Confirmed / OK |
| `red` | Reject |

**Attendance Status:** `invited` · `registered` · `attended` · `no_show` · `cancelled`

> **Notes:** Tidak perlu membuat kolom `usage_count`, karena jumlah pemakaian bisa dihitung dari tabel `event_leads`:
> ```sql
> SELECT contact_id, COUNT(*) AS usage_count
> FROM event_leads
> GROUP BY contact_id;
> ```

---

## 4. Additional Tables

### 4.1 `removal_requests`

Tabel untuk menyimpan request penghapusan data atau opt-out.

**Use case:** Contact resign, pensiun, meninggal, minta takeout, pindah kerja, atau data tidak boleh dipakai lagi.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `contact_id` | FK | Relasi ke `contacts.id` |
| `reason` | ENUM / VARCHAR | Alasan removal |
| `requested_by` | VARCHAR | Siapa yang request |
| `request_date` | DATE | Tanggal request |
| `source_db` | VARCHAR | Database asal |
| `notes` | TEXT | Catatan tambahan |
| `status` | ENUM / VARCHAR | Status request |
| `created_at` | TIMESTAMP | Waktu data dibuat |

**Reason:** `resign` · `pensiun` · `meninggal` · `requested_takeout` · `pindah_kerja` · `lainnya`

**Status:** `pending` · `approved` · `rejected` · `done`

**Logic:** Jika removal request sudah `done`, maka:
- `contacts.is_active = false`
- Contact tidak muncul di pencarian lead aktif
- History lama di `event_leads` tetap ada untuk audit trail

---

### 4.2 `personal_email_domains`

Tabel untuk menyimpan daftar domain email personal.

**Use case:** Validasi email corporate, warning jika user input email personal, deteksi suspicious identity.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `domain` | VARCHAR | Domain email personal |
| `risk_level` | VARCHAR | Level risiko |
| `notes` | TEXT | Catatan tambahan |

**Contoh data:** `gmail.com`, `yahoo.com`, `hotmail.com`, `outlook.com`, `icloud.com`, `proton.me`, `live.com`, `aol.com`

> **Logic:** Jika email domain masuk ke tabel ini, sistem kasih warning: *"Email personal terdeteksi. Untuk event corporate, minta email kantor."*

---

### 4.3 `flagged_identities`

Tabel untuk menyimpan suspicious identity atau "tikus".

**Use case:** Orang daftar berkali-kali pakai nama berbeda, email berbeda tapi nomor HP sama, pakai email personal untuk event corporate, fake company, duplicate phone/email.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `contact_id` | FK | Relasi ke `contacts.id`, nullable |
| `event_id` | FK | Relasi ke `events.id`, nullable |
| `name_used` | VARCHAR | Nama yang dipakai |
| `email_used` | VARCHAR | Email yang dipakai |
| `phone_used` | VARCHAR | Nomor yang dipakai |
| `flag_reason` | ENUM / VARCHAR | Alasan flag |
| `linked_flag_id` | FK | Relasi ke flagged identity lain |
| `evidence_notes` | TEXT | Bukti / catatan |
| `status` | ENUM / VARCHAR | Status flag |
| `created_at` | TIMESTAMP | Waktu data dibuat |
| `updated_at` | TIMESTAMP | Waktu data terakhir diupdate |

**Flag Reason:** `multiple_identity` · `fake_company` · `no_corporate_email` · `duplicate_phone` · `duplicate_email` · `suspicious_repeated_attendance`

**Status:** `suspected` · `confirmed` · `cleared`

**Logic auto-flag:**
- Nomor HP sama tapi nama beda → `duplicate_phone`
- Email sama tapi nama beda → `duplicate_email`
- Email personal untuk event corporate → `no_corporate_email`
- Contact muncul di banyak event dalam waktu pendek → `suspicious_repeated_attendance`

---

## 5. Optional Tables

### 5.1 `industries`

Standarisasi data industri.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `name` | VARCHAR | Nama industri |
| `code` | VARCHAR | Kode industri |
| `notes` | TEXT | Catatan |

**Contoh:** FSI, Manufacturing, Mining, Retail, Healthcare, Education, Government, Technology

---

### 5.2 `import_batches`

Tracking proses import Excel.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `file_name` | VARCHAR | Nama file import |
| `source` | VARCHAR | Sumber import |
| `imported_by` | VARCHAR | User yang import |
| `total_rows` | INTEGER | Total row |
| `success_rows` | INTEGER | Row berhasil |
| `failed_rows` | INTEGER | Row gagal |
| `duplicate_rows` | INTEGER | Row duplicate |
| `created_at` | TIMESTAMP | Waktu import |

---

### 5.3 `import_rows`

Tracking detail row import.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `import_batch_id` | FK | Relasi ke `import_batches.id` |
| `row_number` | INTEGER | Nomor baris Excel |
| `raw_data` | JSONB | Data mentah dari Excel |
| `status` | VARCHAR | Status row |
| `error_message` | TEXT | Error jika gagal |
| `created_contact_id` | FK | Contact yang dibuat |
| `created_company_id` | FK | Company yang dibuat |
| `created_at` | TIMESTAMP | Waktu data dibuat |

**Status:** `success` · `failed` · `duplicate` · `skipped`

---

### 5.4 `audit_logs`

Mencatat perubahan penting.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID / BIGSERIAL | Primary key |
| `entity_type` | VARCHAR | Nama tabel / entity |
| `entity_id` | UUID | ID data yang berubah |
| `action` | VARCHAR | Action |
| `old_data` | JSONB | Data sebelum update |
| `new_data` | JSONB | Data setelah update |
| `changed_by` | VARCHAR | User yang melakukan perubahan |
| `created_at` | TIMESTAMP | Waktu perubahan |

**Action:** `create` · `update` · `delete` · `merge` · `flag` · `unflag` · `approve_removal`

---

## 6. PostgreSQL Enum Values

```sql
CREATE TYPE contact_type_enum AS ENUM (
  'partner_it', 'partner_marketing', 'end_user', 'unknown'
);

CREATE TYPE contact_source_enum AS ENUM (
  'contactout', 'old_db', 'manual', 'excel_import', 'event_registration'
);

CREATE TYPE event_type_enum AS ENUM (
  'partner', 'end_user', 'internal', 'other'
);

CREATE TYPE lead_status_enum AS ENUM (
  'white', 'yellow', 'green', 'red'
);

CREATE TYPE attendance_status_enum AS ENUM (
  'invited', 'registered', 'attended', 'no_show', 'cancelled'
);

CREATE TYPE removal_reason_enum AS ENUM (
  'resign', 'pensiun', 'meninggal', 'requested_takeout', 'pindah_kerja', 'lainnya'
);

CREATE TYPE removal_status_enum AS ENUM (
  'pending', 'approved', 'rejected', 'done'
);

CREATE TYPE flag_reason_enum AS ENUM (
  'multiple_identity', 'fake_company', 'no_corporate_email',
  'duplicate_phone', 'duplicate_email', 'suspicious_repeated_attendance'
);

CREATE TYPE flag_status_enum AS ENUM (
  'suspected', 'confirmed', 'cleared'
);
```

---

## 7. Important Indexes

```sql
CREATE INDEX idx_groups_name ON groups(name);
CREATE INDEX idx_companies_group_id ON companies(group_id);
CREATE INDEX idx_companies_name ON companies(name);
CREATE INDEX idx_companies_brand_name ON companies(brand_name);
CREATE INDEX idx_companies_industry ON companies(industry);
CREATE INDEX idx_companies_city ON companies(city);

CREATE INDEX idx_contacts_company_id ON contacts(company_id);
CREATE INDEX idx_contacts_first_name ON contacts(first_name);
CREATE INDEX idx_contacts_last_name ON contacts(last_name);
CREATE INDEX idx_contacts_mobile_phone ON contacts(mobile_phone);
CREATE INDEX idx_contacts_normalized_phone ON contacts(normalized_phone);
CREATE INDEX idx_contacts_is_active ON contacts(is_active);

CREATE INDEX idx_contact_emails_contact_id ON contact_emails(contact_id);
CREATE INDEX idx_contact_emails_email ON contact_emails(email);
CREATE INDEX idx_contact_emails_domain ON contact_emails(domain);

CREATE INDEX idx_events_event_type ON events(event_type);
CREATE INDEX idx_events_date_start ON events(date_start);

CREATE INDEX idx_event_leads_event_id ON event_leads(event_id);
CREATE INDEX idx_event_leads_contact_id ON event_leads(contact_id);
CREATE INDEX idx_event_leads_lead_status ON event_leads(lead_status);
CREATE INDEX idx_event_leads_attendance_status ON event_leads(attendance_status);

CREATE INDEX idx_flagged_identities_contact_id ON flagged_identities(contact_id);
CREATE INDEX idx_flagged_identities_event_id ON flagged_identities(event_id);
CREATE INDEX idx_flagged_identities_status ON flagged_identities(status);
CREATE INDEX idx_flagged_identities_phone_used ON flagged_identities(phone_used);
CREATE INDEX idx_flagged_identities_email_used ON flagged_identities(email_used);
```

---

## 8. Unique Constraints

```sql
ALTER TABLE groups
  ADD CONSTRAINT unique_group_name UNIQUE (name);

ALTER TABLE contact_emails
  ADD CONSTRAINT unique_contact_email UNIQUE (email);

ALTER TABLE event_leads
  ADD CONSTRAINT unique_event_contact UNIQUE (event_id, contact_id);

ALTER TABLE personal_email_domains
  ADD CONSTRAINT unique_personal_email_domain UNIQUE (domain);
```

> **Notes:**
> - `event_leads` harus unique per `event_id + contact_id` supaya satu contact tidak masuk dua kali ke event yang sama.
> - `contact_emails.email` dibuat unique supaya duplicate email bisa terdeteksi.
> - Jika ada case satu email dipakai banyak orang karena generic email seperti `info@company.com`, constraint ini bisa dibuat lebih fleksibel.

---

## 9. Feature Requirements

### Phase 1 — Basic CRM & Lead Database

1. Contact list
2. Company list
3. Group / holding list
4. Contact detail
5. Company detail
6. Import Excel
7. Search contact by: name, company, group, email, phone, industry, city, job title
8. Create event
9. Add contact to event
10. Update lead status: `white` · `yellow` · `green` · `red`
11. View contact event history

### Phase 2 — Data Quality

1. Duplicate contact detection
2. Duplicate company detection
3. Normalize phone number
4. Validate email domain
5. Merge duplicate contacts
6. Merge duplicate companies
7. Show warning for personal email
8. Show warning for duplicate phone
9. Show warning for duplicate email

### Phase 3 — Removal / Takeout Workflow

1. Create removal request
2. Approve removal request
3. Reject removal request
4. Mark contact as inactive
5. Exclude inactive contacts from active lead search
6. Show warning if inactive contact still exists in active event
7. Keep audit trail

### Phase 4 — Suspicious Identity / Tikus Detection

1. Auto-flag duplicate phone with different name
2. Auto-flag duplicate email with different name
3. Auto-flag personal email for corporate event
4. Auto-flag repeated attendance in short period
5. Manual confirm suspicious identity
6. Manual clear false-positive flag
7. Watchlist / blacklist warning
8. Tikus score

---

## 10. Tikus Score

| Condition | Score |
|-----------|-------|
| Duplicate phone with different name | +50 |
| Duplicate email with different name | +40 |
| Personal email for corporate event | +15 |
| Fake company suspected | +30 |
| Joined 3 events within 30 days | +30 |
| Confirmed suspicious before | +100 |

**Risk level:**

| Score | Status |
|-------|--------|
| 0 – 30 | 🟢 Low risk |
| 31 – 69 | 🟡 Medium risk |
| 70 – 99 | 🔴 High risk |
| 100+ | ⛔ Blacklist candidate |

---

## 11. Excel Import Mapping

| Excel Column | Target Table | Target Column |
|-------------|-------------|----------------|
| Nama Group/Holding Company | `groups` | `name` |
| Nama Brand | `companies` | `brand_name` |
| Company Name | `companies` | `name` |
| Address | `companies` | `address` |
| Office Phone | `companies` | `office_phone` |
| Company Website | `companies` | `website` |
| Industry | `companies` | `industry` |
| Company Size (Revenue) | `companies` | `company_size_revenue` |
| Company Size (Employee) | `companies` | `company_size_employee` |
| Company Hardware | `companies` | `company_hardware` |
| City | `companies` | `city` |
| Salutation | `contacts` | `salutation` |
| First Name | `contacts` | `first_name` |
| Last Name | `contacts` | `last_name` |
| Position | `contacts` | `position_level` |
| Speciality/Division | `contacts` | `speciality_division` |
| Jobtitle | `contacts` | `job_title` |
| Mobile Phone | `contacts` | `mobile_phone` |
| Linkedin Link | `contacts` | `linkedin_url` |
| Company Email Address | `contact_emails` | `email`, `email_type = company` |
| Personal Email Address | `contact_emails` | `email`, `email_type = personal` |

---

## 12. Recommended Development Order

### Step 1 — Schema
Buat PostgreSQL schema: `groups`, `companies`, `contacts`, `contact_emails`, `events`, `event_leads`

### Step 2 — Basic CRUD
Group CRUD, Company CRUD, Contact CRUD, Event CRUD, Event Lead CRUD

### Step 3 — Excel Import
Import harus:
- Auto-create group, company, contact, contact email
- Detect duplicate email & phone

### Step 4 — Event Lead Workflow
- Add lead to event
- Update lead status & attendance status
- Show event lead list
- Show contact event history

### Step 5 — Data Quality Tools
- Duplicate detector
- Merge contact & company
- Phone normalizer
- Email domain validator

### Step 6 — Removal & Suspicious Identity
- Removal workflow
- Flagged identities
- Tikus score
- Watchlist warning

---

## 13. Query Examples

### Get contact with company and group
```sql
SELECT
  c.id,
  c.first_name,
  c.last_name,
  c.job_title,
  co.name AS company_name,
  g.name  AS group_name
FROM contacts c
LEFT JOIN companies co ON co.id = c.company_id
LEFT JOIN groups g     ON g.id  = co.group_id
WHERE c.is_active = true;
```

### Get contact event history
```sql
SELECT
  e.name            AS event_name,
  e.event_type,
  e.date_start,
  el.lead_status,
  el.attendance_status,
  el.notes
FROM event_leads el
JOIN events e ON e.id = el.event_id
WHERE el.contact_id = :contact_id
ORDER BY e.date_start DESC;
```

### Get lead usage count
```sql
SELECT
  c.id,
  c.first_name,
  c.last_name,
  COUNT(el.id) AS usage_count
FROM contacts c
LEFT JOIN event_leads el ON el.contact_id = c.id
GROUP BY c.id, c.first_name, c.last_name
ORDER BY usage_count DESC;
```

### Find duplicate phones
```sql
SELECT
  normalized_phone,
  COUNT(*) AS total
FROM contacts
WHERE normalized_phone IS NOT NULL
GROUP BY normalized_phone
HAVING COUNT(*) > 1;
```

### Find personal emails
```sql
SELECT
  ce.email,
  ce.domain,
  c.first_name,
  c.last_name
FROM contact_emails ce
JOIN contacts c               ON c.id  = ce.contact_id
JOIN personal_email_domains ped ON ped.domain = ce.domain;
```

---

## 14. Final Notes

Database ini dibuat agar scalable dari Excel-based lead database menjadi structured PostgreSQL database.

**Hal paling penting:**

1. Jangan simpan semua data dalam satu tabel besar.
2. Status warna harus ada di `event_leads`.
3. Contact bisa dipakai berkali-kali di event berbeda.
4. History tidak boleh hilang.
5. Delete request sebaiknya soft delete.
6. Email personal harus ditandai.
7. Duplicate phone dan email harus bisa dideteksi.
8. Suspicious identity perlu dipisah dari contact normal.
9. Import Excel harus punya audit trail.
10. Merge duplicate perlu dibuat manual, jangan full otomatis.
