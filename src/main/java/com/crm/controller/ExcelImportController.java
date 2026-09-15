package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/databases")
public class ExcelImportController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CompanyBranchRepository companyBranchRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    @Autowired
    private com.crm.service.SuspiciousIdentityService suspiciousIdentityService;

    @Autowired
    private com.crm.service.SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @PostMapping("/import")
    @Transactional
    public ResponseEntity<?> importDatabases(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden: Only ADMIN or MANAGER can import database records"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        // Re-run exactly the preview validation inside the import transaction.
        ResponseEntity<?> validation = previewImport(file);
        if (!validation.getStatusCode().is2xxSuccessful()) return validation;
        ImportPreviewResponse preview = (ImportPreviewResponse) validation.getBody();
        List<RowPreview> invalidRows = preview.getRows().stream()
                .filter(row -> !Set.of("NEW", "DUPLICATE").contains(row.getStatus())).toList();
        Map<Integer, RowPreview> validatedRows = new LinkedHashMap<>();
        preview.getRows().stream().filter(row -> Set.of("NEW", "DUPLICATE").contains(row.getStatus()))
                .forEach(row -> validatedRows.put(row.getRowNum(), row));
        if (validatedRows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Tidak ada baris bersih untuk diimport. Perbaiki baris yang dilewati lalu upload ulang.",
                    "count", 0, "skippedCount", invalidRows.size(), "skippedRows", invalidRows));
        }

        int successCount = 0;
        int newCount = 0;
        int updatedCount = 0;
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            List<Company> knownCompanies = new ArrayList<>(companyRepository.findAll());
            List<CompanyBranch> knownBranches = new ArrayList<>(companyBranchRepository.findAll());
            
            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }

                String groupName = normalizeField(getCellValueAsString(row.getCell(1)));
                String brandName = normalizeField(getCellValueAsString(row.getCell(2)));
                String companyName = cleanCompanyName(normalizeField(getCellValueAsString(row.getCell(3))));
                String salutation = normalizeField(getCellValueAsString(row.getCell(4)));
                String firstName = normalizeField(getCellValueAsString(row.getCell(5)));
                String lastName = normalizeField(getCellValueAsString(row.getCell(6)));
                String positionStr = normalizeField(getCellValueAsString(row.getCell(7)));
                String specialityDivision = normalizeField(getCellValueAsString(row.getCell(8)));
                String jobTitle = normalizeField(getCellValueAsString(row.getCell(9)));
                String address = normalizeField(getCellValueAsString(row.getCell(10)));
                String officePhone = normalizeOfficePhone(getCellValueAsString(row.getCell(11)));
                String mobilePhone = cleanPhone(getCellValueAsString(row.getCell(12)));
                String companyEmail = normalizeField(getCellValueAsString(row.getCell(13)));
                String personalEmail = normalizeField(getCellValueAsString(row.getCell(14)));
                String industry = normalizeField(getCellValueAsString(row.getCell(15)));
                String sizeRevenue = normalizeField(getCellValueAsString(row.getCell(16)));
                String sizeEmployee = normalizeField(getCellValueAsString(row.getCell(17)));
                String hardware = normalizeField(getCellValueAsString(row.getCell(18)));
                String linkedinUrl = normalizeField(getCellValueAsString(row.getCell(19)));
                String city = cell(row, 20);
                String postalCode = cell(row, 21);
                String website = cell(row, 22);

                if (!validatedRows.containsKey(row.getRowNum() + 1)) continue;

                RowPreview validated = validatedRows.get(row.getRowNum() + 1);
                String branchName = Objects.toString(validated.getBranchName(), "");
                Long existingId = validated.getExistingDatabaseId();
                Database targetDb = existingId == null ? null : databaseRepository.findById(existingId)
                        .orElseThrow(() -> new IllegalStateException("Kontak target sudah tidak tersedia: " + existingId));

                // Preserve existing company ownership; only create a group when it can be assigned.
                Company company = findCompany(knownCompanies, companyName);
                if (targetDb != null && targetDb.getBranch() != null && (company == null
                        || !Objects.equals(targetDb.getBranch().getCompanyId(), company.getId())
                        || !normalizeKey(targetDb.getBranch().getName()).equals(normalizeKey(branchName)))) {
                    throw new IllegalStateException("Penugasan cabang kontak berubah. Preview ulang sebelum import.");
                }
                CompanyBranch branch = findBranch(knownBranches, company, branchName);
                if (branch != null && !branchLocationConflicts(row, branch).isEmpty()) {
                    throw new IllegalStateException("Lokasi cabang berubah. Preview ulang sebelum import.");
                }
                Group group = company == null ? null : company.getGroup();
                if (group == null && !groupName.isEmpty()) {
                    group = groupRepository.findByNameIgnoreCase(groupName).orElse(null);
                    if (group == null) {
                        group = Group.builder().name(groupName).build();
                        group = groupRepository.save(group);
                    }
                }

                // 2. Resolve Company
                if (!companyName.isEmpty()) {
                    if (company == null) {
                        company = Company.builder()
                                .name(companyName)
                                .brandName(brandName.isEmpty() ? null : brandName)
                                .address(!branchName.isEmpty() || address.isEmpty() ? null : address)
                                .officePhone(!branchName.isEmpty() || officePhone.isEmpty() ? null : officePhone)
                                .website(website.isEmpty() ? null : website)
                                .industry(industry.isEmpty() ? null : industry)
                                .companySizeRevenue(sizeRevenue.isEmpty() ? null : sizeRevenue)
                                .companySizeEmployee(sizeEmployee.isEmpty() ? null : sizeEmployee)
                                .companyHardware(hardware.isEmpty() ? null : hardware)
                                .city(!branchName.isEmpty() || city.isEmpty() ? null : city)
                                .postalCode(!branchName.isEmpty() || postalCode.isEmpty() ? null : postalCode)
                                .group(group)
                                .build();
                        company = companyRepository.save(company);
                        knownCompanies.add(company);
                    } else {
                        if (!brandName.isEmpty() && normalizeField(company.getBrandName()).isEmpty()) company.setBrandName(brandName);
                        if (branchName.isEmpty() && !address.isEmpty() && normalizeField(company.getAddress()).isEmpty()) company.setAddress(address);
                        if (branchName.isEmpty() && !officePhone.isEmpty() && (normalizeField(company.getOfficePhone()).isEmpty()
                                || officePhone.equals(normalizeOfficePhone(company.getOfficePhone())))) {
                            company.setOfficePhone(officePhone);
                        }
                        if (!website.isEmpty() && normalizeField(company.getWebsite()).isEmpty()) company.setWebsite(website);
                        if (!industry.isEmpty() && normalizeField(company.getIndustry()).isEmpty()) company.setIndustry(industry);
                        if (!sizeRevenue.isEmpty() && normalizeField(company.getCompanySizeRevenue()).isEmpty()) company.setCompanySizeRevenue(sizeRevenue);
                        if (!sizeEmployee.isEmpty() && normalizeField(company.getCompanySizeEmployee()).isEmpty()) company.setCompanySizeEmployee(sizeEmployee);
                        if (!hardware.isEmpty() && normalizeField(company.getCompanyHardware()).isEmpty()) company.setCompanyHardware(hardware);
                        if (branchName.isEmpty() && !city.isEmpty() && normalizeField(company.getCity()).isEmpty()) company.setCity(city);
                        if (branchName.isEmpty() && !postalCode.isEmpty() && normalizeField(company.getPostalCode()).isEmpty()) company.setPostalCode(postalCode);
                        if (group != null && company.getGroup() == null) company.setGroup(group);
                        company = companyRepository.save(company);
                    }
                }

                if (!branchName.isEmpty()) {
                    if (company == null || company.getId() == null) throw new IllegalStateException("Perusahaan cabang belum tersedia");
                    if (branch == null) {
                        branch = CompanyBranch.builder().companyId(company.getId()).name(branchName).build();
                    }
                    if (normalizeField(branch.getAddress()).isEmpty() && !address.isEmpty()) branch.setAddress(address);
                    if (normalizeField(branch.getOfficePhone()).isEmpty() && !officePhone.isEmpty()) branch.setOfficePhone(officePhone);
                    if (normalizeField(branch.getCity()).isEmpty() && !city.isEmpty()) branch.setCity(city);
                    if (normalizeField(branch.getPostalCode()).isEmpty() && !postalCode.isEmpty()) branch.setPostalCode(postalCode);
                    boolean newBranch = branch.getId() == null;
                    branch = companyBranchRepository.save(branch);
                    if (newBranch) knownBranches.add(branch);
                }

                // 3. Find existing Database record or create new
                PositionLevel posLevel = PositionLevel.fromValue(positionStr);
                String normPhone = formatNormalizedPhone(mobilePhone);

                if (targetDb != null) {
                    if (!salutation.isEmpty()) targetDb.setSalutation(salutation);
                    if (!firstName.isEmpty()) targetDb.setFirstName(firstName);
                    if (!lastName.isEmpty()) targetDb.setLastName(lastName);
                    if (!positionStr.isEmpty()) targetDb.setPositionLevel(posLevel);
                    if (!specialityDivision.isEmpty()) targetDb.setSpecialityDivision(specialityDivision);
                    if (!jobTitle.isEmpty()) targetDb.setJobTitle(jobTitle);
                    if (!mobilePhone.isEmpty()) targetDb.setMobilePhone(mobilePhone);
                    if (normPhone != null) targetDb.setNormalizedPhone(normPhone);
                    if (!linkedinUrl.isEmpty()) targetDb.setLinkedinUrl(linkedinUrl);
                    if (company != null) targetDb.setCompany(company);
                    if (branch != null) targetDb.setBranch(branch);
                    targetDb = databaseRepository.save(targetDb);
                    updatedCount++;
                } else {
                    targetDb = Database.builder()
                            .createdByUserId(currentUser.getId())
                            .entryMethod("excel_import")
                            .salutation(salutation.isEmpty() ? null : salutation)
                            .firstName(firstName)
                            .lastName(lastName.isEmpty() ? null : lastName)
                            .positionLevel(posLevel)
                            .specialityDivision(specialityDivision.isEmpty() ? null : specialityDivision)
                            .jobTitle(jobTitle.isEmpty() ? null : jobTitle)
                            .mobilePhone(mobilePhone.isEmpty() ? null : mobilePhone)
                            .normalizedPhone(normPhone)
                            .linkedinUrl(linkedinUrl.isEmpty() ? null : linkedinUrl)
                            .isActive(true)
                            .company(company)
                            .branch(branch)
                            .build();
                    targetDb = databaseRepository.save(targetDb);
                    newCount++;
                }

                // Merge email lists by their Excel column; never infer ownership from the domain.
                mergeEmails(targetDb, splitEmailTokens(companyEmail), true);
                mergeEmails(targetDb, splitEmailTokens(personalEmail), false);
                // Retyping an address can remove a type's old primary; select a retained address if needed.
                for (boolean corporate : List.of(true, false)) {
                    List<DatabaseEmail> typedEmails = emails(targetDb).stream()
                            .filter(email -> isCompanyEmail(email) == corporate).toList();
                    if (typedEmails.isEmpty()) continue;
                    DatabaseEmail primary = typedEmails.stream().filter(email -> Boolean.TRUE.equals(email.getIsPrimary()))
                            .findFirst().orElse(typedEmails.get(0));
                    for (DatabaseEmail email : typedEmails) {
                        if (Boolean.TRUE.equals(email.getIsPrimary()) != (email == primary)) {
                            email.setIsPrimary(email == primary);
                            databaseEmailRepository.save(email);
                        }
                    }
                }

                suspiciousIdentityService.checkAndFlagDatabase(targetDb);
                successCount++;
            }

            auditLogService.recordUserAction(
                    currentUser,
                    "DATABASE",
                    "IMPORT",
                    null,
                    file.getOriginalFilename(),
                    "Import Excel '" + safeFileName(file.getOriginalFilename()) + "': " + newCount
                            + " kontak baru, " + updatedCount + " update, " + invalidRows.size() + " baris dilewati"
            );

            return ResponseEntity.ok(Map.of(
                "message", successCount + " baris berhasil diproses; " + invalidRows.size() + " baris kotor dilewati.",
                "count", successCount,
                "newCount", newCount,
                "updatedCount", updatedCount,
                "totalRows", preview.getTotalRows(),
                "skippedCount", invalidRows.size(),
                "skippedRows", invalidRows
            ));
            
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.internalServerError().body(Map.of(
                "message", "Import dibatalkan; seluruh perubahan di-rollback: " + e.getMessage(),
                "error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())
            ));
        }
    }

    @PostMapping("/import/preview")
    public ResponseEntity<?> previewImport(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            int branchColumn = validateHeaders(sheet.getRow(0));
            // ponytail: one snapshot, O(rows * contacts); index identities if large imports become slow.
            List<Database> databases = databaseRepository.findAll();
            List<Company> knownCompanies = companyRepository.findAll();
            List<CompanyBranch> knownBranches = companyBranchRepository.findAll();
            List<RowPreview> previews = new ArrayList<>();
            Map<String, List<RowPreview>> fileOwners = new LinkedHashMap<>();
            Map<String, List<RowPreview>> companyRows = new LinkedHashMap<>();
            Map<String, Map<Integer, String>> companyValues = new LinkedHashMap<>();
            Map<String, Set<Integer>> companyConflicts = new LinkedHashMap<>();

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row)) continue;

                String groupName = cell(row, 1);
                String companyName = cleanCompanyName(cell(row, 3));
                String branchName = branchColumn < 0 ? "" : normalizeField(getCellValueAsString(row.getCell(branchColumn)));
                String firstName = cell(row, 5);
                String lastName = cell(row, 6);
                String mobilePhone = cleanPhone(cell(row, 12));
                String companyEmail = cell(row, 13);
                String personalEmail = cell(row, 14);
                RowPreview preview = RowPreview.builder()
                        .rowNum(r + 1).groupName(groupName).companyName(companyName).branchName(branchName)
                        .firstName(firstName).lastName(lastName).jobTitle(cell(row, 9))
                        .companyEmail(companyEmail).personalEmail(personalEmail).mobilePhone(mobilePhone)
                        .email(companyEmail.isEmpty() ? personalEmail : companyEmail)
                        .status("NEW").message("").build();
                previews.add(preview);
                validateCompanyFieldLengths(row, preview);
                if (branchName.codePointCount(0, branchName.length()) > 255) {
                    conflict(preview, "Nama Cabang/Kantor maksimal 255 karakter. Perbaiki isi sel lalu upload ulang.");
                }

                for (String email : emailTokens(companyEmail + ";" + personalEmail)) {
                    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                        conflict(preview, "Format email tidak valid: " + email);
                    }
                }
                List<String> invalidPersonal = getCorporateEmailsInPersonalColumn(personalEmail);
                if (!invalidPersonal.isEmpty()) {
                    conflict(preview, "Email kantor tidak boleh berada di kolom Personal Email: " + String.join(", ", invalidPersonal));
                }
                Set<String> personal = new LinkedHashSet<>(splitEmailTokens(personalEmail));
                Set<String> corporate = new LinkedHashSet<>(splitEmailTokens(companyEmail));
                if (!Collections.disjoint(personal, corporate)) {
                    conflict(preview, "Email yang sama tidak boleh di kolom Company dan Personal sekaligus.");
                }

                Database target = null;
                Company matchedCompany = null;
                CompanyBranch matchedBranch = null;
                try {
                    matchedCompany = findCompany(knownCompanies, companyName);
                    target = findExistingDatabase(databases, firstName, lastName, companyName, personal, mobilePhone);
                    if (target != null) preview.setExistingDatabaseId(target.getId());
                    if (target != null && target.getBranch() != null) {
                        CompanyBranch retainedBranch = target.getBranch();
                        if (matchedCompany == null || !Objects.equals(retainedBranch.getCompanyId(), matchedCompany.getId())
                                || (!branchName.isEmpty() && !normalizeKey(branchName).equals(normalizeKey(retainedBranch.getName())))) {
                            conflict(preview, "Kontak sudah terdaftar di cabang " + retainedBranch.getName()
                                    + ". Pindahkan perusahaan/cabang melalui form kontak sebelum import.");
                        } else if (branchName.isEmpty()) {
                            branchName = retainedBranch.getName();
                        }
                    }
                    matchedBranch = findBranch(knownBranches, matchedCompany, branchName);
                    preview.setBranchName(matchedBranch == null ? branchName : matchedBranch.getName());
                    if (matchedBranch != null) {
                        for (String field : branchLocationConflicts(row, matchedBranch)) {
                            conflict(preview, field + " berbeda dari data cabang " + matchedBranch.getName()
                                    + ". Perbaiki Excel atau ubah lokasi cabang melalui Company Details sebelum import.");
                        }
                    }
                } catch (IllegalArgumentException e) {
                    conflict(preview, e.getMessage());
                }

                List<String> missing = getMissingMandatoryFields(groupName, cell(row, 2), companyName,
                        cell(row, 4), firstName, lastName, cell(row, 7), cell(row, 9), cell(row, 10),
                        cleanPhone(cell(row, 11)), mobilePhone, companyEmail, cell(row, 15), cell(row, 20), cell(row, 22));
                if (target != null) {
                    // A blank update is valid only if the retained value actually fills that field.
                    Database retained = target;
                    Company retainedCompany = matchedCompany;
                    CompanyBranch retainedBranch = matchedBranch;
                    boolean branchLocation = !preview.getBranchName().isEmpty();
                    missing.removeIf(field -> hasRetainedValue(field, retained, retainedCompany, retainedBranch, branchLocation, personal));
                }
                CompanyBranch availableBranch = matchedBranch;
                if (availableBranch != null) missing.removeIf(field -> !normalizeField(branchLocationValue(field, availableBranch)).isEmpty());
                if (!missing.isEmpty()) {
                    if ("NEW".equals(preview.getStatus())) preview.setStatus("INCOMPLETE");
                    addMessage(preview, "Kolom kosong: " + String.join(", ", missing));
                }

                String normalizedPhone = formatNormalizedPhone(mobilePhone);
                for (Database other : databases) {
                    if (target != null && Objects.equals(target.getId(), other.getId())) continue;
                    if (normalizedPhone != null && (normalizedPhone.equals(formatNormalizedPhone(other.getMobilePhone()))
                            || normalizedPhone.equals(formatNormalizedPhone(other.getNormalizedPhone())))) {
                        conflict(preview, "Mobile Phone sudah dipakai " + contactLabel(other));
                    }
                    for (DatabaseEmail email : emails(other)) {
                        String address = normalizeKey(email.getEmail());
                        if (personal.contains(address) || (corporate.contains(address) && !isCompanyEmail(email))) {
                            conflict(preview, "Email personal '" + address + "' sudah dipakai " + contactLabel(other));
                        }
                    }
                }

                // One contact is updated at most once per workbook; conflicting row order must not choose a winner.
                registerOwner(fileOwners, "Kontak: " + normalizeKey(firstName + " " + lastName)
                        + " / " + normalizeKey(companyName), preview);
                if (target != null) registerOwner(fileOwners, "Target ID: " + target.getId(), preview);
                if (normalizedPhone != null) registerOwner(fileOwners, "Mobile Phone: " + normalizedPhone, preview);
                for (String email : personal) registerOwner(fileOwners, "Personal Email: " + email, preview);

                // Company metadata is shared; locations are compared only within the same office.
                for (boolean location : List.of(false, true)) {
                    String companyKey = normalizeKey(companyName) + (location ? "\u0000" + normalizeKey(preview.getBranchName()) : "");
                    companyRows.computeIfAbsent(companyKey, key -> new ArrayList<>()).add(preview);
                    Map<Integer, String> values = companyValues.computeIfAbsent(companyKey, key -> new LinkedHashMap<>());
                    for (int column : location ? List.of(10, 11, 20, 21) : List.of(1, 2, 15, 16, 17, 18, 22)) {
                        String value = companyCellValue(row, column);
                        if (value.isEmpty()) continue;
                        String previous = values.putIfAbsent(column, value);
                        if (previous != null && !previous.equals(value)) {
                            companyConflicts.computeIfAbsent(companyKey, key -> new LinkedHashSet<>()).add(column);
                        }
                    }
                }
            }

            // Every row of a conflicting company must be skipped, including later rows matching the first.
            companyConflicts.forEach((key, columns) -> columns.forEach(column ->
                    describeCompanyConflict(sheet, companyRows.get(key), column)));

            // Company emails may repeat, except when that address is also claimed as personal in this file.
            for (RowPreview row : previews) {
                for (String email : splitEmailTokens(row.getCompanyEmail())) {
                    if (fileOwners.containsKey("Personal Email: " + email)) {
                        registerOwner(fileOwners, "Personal Email: " + email, row);
                    }
                }
            }
            for (Map.Entry<String, List<RowPreview>> entry : fileOwners.entrySet()) {
                List<RowPreview> owners = entry.getValue().stream().distinct().toList();
                if (owners.size() < 2) continue;
                String rows = String.join(", ", owners.stream().map(row -> String.valueOf(row.getRowNum())).toList());
                owners.forEach(row -> conflict(row, entry.getKey() + " berulang di baris Excel " + rows + "."));
            }
            for (RowPreview preview : previews) {
                if ("NEW".equals(preview.getStatus()) && preview.getExistingDatabaseId() != null) {
                    preview.setStatus("DUPLICATE");
                    addMessage(preview, "Update kontak ID " + preview.getExistingDatabaseId()
                            + ". Nilai kosong dan email lama dipertahankan; data master company/cabang hanya dilengkapi jika kosong.");
                } else if ("NEW".equals(preview.getStatus())) {
                    addMessage(preview, "Akan disimpan sebagai kontak baru. Company/cabang yang sudah ada hanya dilengkapi jika kosong.");
                }
            }
            if (previews.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "File Excel tidak memiliki baris data."));
            }
            return ResponseEntity.ok(ImportPreviewResponse.builder()
                    .totalRows(previews.size())
                    .newCount(countStatus(previews, "NEW"))
                    .duplicateCount(countStatus(previews, "DUPLICATE"))
                    .incompleteCount(countStatus(previews, "INCOMPLETE"))
                    .conflictCount(countStatus(previews, "CONFLICT"))
                    .rows(previews).build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Gagal memvalidasi Excel: " + e.getMessage()));
        }
    }

    private String companyCellValue(Row row, int column) {
        return normalizeKey(column == 11 ? normalizeOfficePhone(cell(row, column)) : cell(row, column));
    }

    private void describeCompanyConflict(Sheet sheet, List<RowPreview> rows, int column) {
        boolean branchScope = List.of(10, 11, 20, 21).contains(column) && !rows.get(0).getBranchName().isEmpty();
        String scope = branchScope ? "cabang " + rows.get(0).getBranchName() : "perusahaan ini";
        String subject = branchScope ? "cabang" : "perusahaan";
        Map<String, List<RowPreview>> variants = new LinkedHashMap<>();
        for (RowPreview row : rows) {
            String value = companyCellValue(sheet.getRow(row.getRowNum() - 1), column);
            if (!value.isEmpty()) variants.computeIfAbsent(value, ignored -> new ArrayList<>()).add(row);
        }
        int largest = variants.values().stream().mapToInt(List::size).max().orElse(0);
        boolean uniqueLargest = variants.values().stream().filter(group -> group.size() == largest).count() == 1;
        String details = String.join("; ", variants.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getValue().size()))
                .map(entry -> "'" + entry.getKey() + "' dipakai " + entry.getValue().size() + " baris: "
                        + String.join(", ", entry.getValue().stream().limit(3)
                        .map(row -> "#" + row.getRowNum() + " " + (row.getFirstName() + " " + row.getLastName()).trim()).toList())
                        + (entry.getValue().size() > 3 ? " dan " + (entry.getValue().size() - 3) + " baris lainnya" : ""))
                .toList());
        for (RowPreview row : rows) {
            String value = companyCellValue(sheet.getRow(row.getRowNum() - 1), column);
            int count = variants.getOrDefault(value, List.of()).size();
            String reason = uniqueLargest && count > 0 && count < largest
                    ? "Periksa nilai berbeda: "
                    : "Ikut tertahan karena perbedaan data " + subject + ": ";
            conflict(row, reason + HEADERS.get(column) + " baris ini "
                    + (value.isEmpty() ? "kosong" : "'" + value + "'") + ". " + details
                    + ". Pastikan nilai yang benar lalu samakan baris untuk lokasi " + subject + " yang sama."
                    + " Semua " + rows.size() + " baris " + scope + " ditahan; nilai terbanyak belum tentu benar.");
        }
    }

    private Company findCompany(List<Company> companies, String companyName) {
        List<Company> matches = companies.stream()
                .filter(company -> normalizeKey(cleanCompanyName(company.getName())).equals(normalizeKey(companyName))).toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Company ambigu: " + companyName + " (ID "
                    + String.join(", ", matches.stream().map(company -> String.valueOf(company.getId())).toList()) + ").");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private CompanyBranch findBranch(List<CompanyBranch> branches, Company company, String name) {
        if (company == null || name.isEmpty()) return null;
        List<CompanyBranch> matches = branches.stream()
                .filter(branch -> Objects.equals(company.getId(), branch.getCompanyId())
                        && normalizeKey(name).equals(normalizeKey(branch.getName()))).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Cabang ambigu: " + name + ". Rapikan cabang duplikat sebelum import.");
        return matches.isEmpty() ? null : matches.get(0);
    }

    private List<String> branchLocationConflicts(Row row, CompanyBranch branch) {
        List<String> conflicts = new ArrayList<>();
        for (int column : List.of(10, 11, 20, 21)) {
            String incoming = companyCellValue(row, column);
            String retained = normalizeKey(normalizeField(branchLocationValue(HEADERS.get(column), branch)));
            if (!incoming.isEmpty() && !retained.isEmpty() && !incoming.equals(retained)) conflicts.add(HEADERS.get(column));
        }
        return conflicts;
    }

    private String branchLocationValue(String field, CompanyBranch branch) {
        if (branch == null) return null;
        return switch (field) {
            case "Address" -> branch.getAddress();
            case "Office Phone" -> normalizeOfficePhone(branch.getOfficePhone());
            case "City" -> branch.getCity();
            case "Postal Code" -> branch.getPostalCode();
            default -> null;
        };
    }

    private boolean hasRetainedValue(String field, Database contact, Company company, CompanyBranch branch,
                                     boolean branchLocation, Set<String> incomingPersonal) {
        String value = switch (field) {
            case "Salutation" -> contact.getSalutation();
            case "Last Name" -> contact.getLastName();
            case "Position" -> contact.getPositionLevel() == null ? null : contact.getPositionLevel().getValue();
            case "Job Title" -> contact.getJobTitle();
            case "Mobile Phone" -> cleanPhone(contact.getMobilePhone());
            case "Company Email" -> emails(contact).stream().filter(ExcelImportController::isCompanyEmail)
                    .map(DatabaseEmail::getEmail).filter(email -> !normalizeField(email).isEmpty()
                            && !incomingPersonal.contains(normalizeKey(email))).findFirst().orElse(null);
            case "Nama Group Holding" -> company == null || company.getGroup() == null ? null : company.getGroup().getName();
            case "Nama Brand" -> company == null ? null : company.getBrandName();
            case "Address" -> branchLocation ? branchLocationValue(field, branch) : company == null ? null : company.getAddress();
            case "Office Phone" -> branchLocation ? branchLocationValue(field, branch) : company == null ? null : cleanPhone(company.getOfficePhone());
            case "Industry" -> company == null ? null : company.getIndustry();
            case "City" -> branchLocation ? branchLocationValue(field, branch) : company == null ? null : company.getCity();
            case "Company Website" -> company == null ? null : company.getWebsite();
            default -> null;
        };
        return !normalizeField(value).isEmpty();
    }

    private Database findExistingDatabase(List<Database> databases, String firstName, String lastName,
                                          String companyName, Set<String> personalEmails, String mobilePhone) {
        String name = normalizeKey(firstName + " " + lastName);
        String phone = formatNormalizedPhone(mobilePhone);
        List<Database> candidates = databases.stream().filter(database -> {
            if (!name.equals(normalizeKey(Objects.toString(database.getFirstName(), "") + " "
                    + Objects.toString(database.getLastName(), "")))) return false;
            String company = database.getCompany() == null ? "" : cleanCompanyName(database.getCompany().getName());
            return normalizeKey(companyName).equals(normalizeKey(company))
                    || (phone != null && (phone.equals(formatNormalizedPhone(database.getMobilePhone()))
                    || phone.equals(formatNormalizedPhone(database.getNormalizedPhone()))))
                    || emails(database).stream().anyMatch(email -> !isCompanyEmail(email)
                    && personalEmails.contains(normalizeKey(email.getEmail())));
        }).toList();
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("Target update ambigu: " + String.join(", ",
                    candidates.stream().map(this::contactLabel).toList()) + ". Rapikan duplikat sebelum import.");
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void mergeEmails(Database target, List<String> incoming, boolean corporate) {
        if (incoming.isEmpty()) return;
        if (target.getEmails() == null) target.setEmails(new ArrayList<>());
        // Clear primaries for this type only; the first incoming address becomes the primary.
        for (DatabaseEmail email : target.getEmails()) {
            if (isCompanyEmail(email) == corporate) {
                email.setIsPrimary(false);
                databaseEmailRepository.save(email);
            }
        }
        for (int i = 0; i < incoming.size(); i++) {
            String address = incoming.get(i);
            List<DatabaseEmail> owners = databaseEmailRepository.findAllByEmailIgnoreCase(address);
            boolean conflict = owners.stream().anyMatch(email -> email.getDatabase() != null
                    && !Objects.equals(target.getId(), email.getDatabase().getId())
                    && (!corporate || !isCompanyEmail(email)));
            if (conflict) throw new IllegalStateException("Email personal sudah dipakai kontak lain: " + address);
            DatabaseEmail email = target.getEmails().stream()
                    .filter(existing -> address.equalsIgnoreCase(existing.getEmail())).findFirst().orElse(null);
            if (email == null) {
                email = DatabaseEmail.builder().email(address).database(target).build();
                target.getEmails().add(email);
            }
            email.setEmailType(corporate ? "company" : "personal");
            email.setIsCorporate(corporate);
            email.setIsPrimary(i == 0);
            databaseEmailRepository.save(email);
        }
    }

    private static boolean isCompanyEmail(DatabaseEmail email) {
        return "company".equalsIgnoreCase(email.getEmailType())
                || (!"personal".equalsIgnoreCase(email.getEmailType()) && Boolean.TRUE.equals(email.getIsCorporate()));
    }

    private static List<DatabaseEmail> emails(Database database) {
        return database.getEmails() == null ? List.of() : database.getEmails();
    }

    private String contactLabel(Database database) {
        return Objects.toString(database.getFirstName(), "") + " " + Objects.toString(database.getLastName(), "")
                + " (ID " + database.getId() + ", "
                + (database.getCompany() == null ? "tanpa company" : database.getCompany().getName()) + ")";
    }

    private static void registerOwner(Map<String, List<RowPreview>> owners, String key, RowPreview row) {
        owners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }

    private static void addMessage(RowPreview row, String message) {
        row.setMessage(row.getMessage().isEmpty() ? message : row.getMessage() + " | " + message);
    }

    private static void conflict(RowPreview row, String message) {
        row.setStatus("CONFLICT");
        addMessage(row, message);
    }

    private static int countStatus(List<RowPreview> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.getStatus())).count();
    }

    private static String normalizeKey(String value) {
        return Objects.toString(value, "").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String cell(Row row, int index) {
        return normalizeField(getCellValueAsString(row.getCell(columnIndex(row, index))));
    }

    private int columnIndex(Row row, int index) {
        // The new template inserts Cabang/Kantor after LinkedIn; legacy columns stay semantic here.
        return index >= 20 && index < HEADERS.size()
                && isBranchHeader(row.getSheet().getRow(0).getCell(20)) ? index + 1 : index;
    }

    private boolean isBlankRow(Row row) {
        for (int column = 1; column < Math.max(HEADERS.size(), row.getLastCellNum()); column++) {
            if (!normalizeField(getCellValueAsString(row.getCell(column))).isEmpty()) return false;
        }
        return true;
    }

    private static final List<String> HEADERS = List.of(
            "No", "Nama Group/Holding Company", "Nama Brand", "Company Name", "Salutation",
            "First Name", "Last Name", "Position", "Division", "Jobtitle", "Address", "Office Phone",
            "Mobile Phone", "Company Email Address", "Personal Email Address", "Industry",
            "Company Size (Revenue)", "Company Size (Employee)", "Company Hardware", "Linkedin Link",
            "City", "Postal Code", "Company Website");

    private void validateCompanyFieldLengths(Row row, RowPreview preview) {
        // Match the bounded company/group columns in the Liquibase schema; never truncate input.
        for (int column = 1; column < HEADERS.size(); column++) {
            int limit = switch (column) {
                case 1, 2, 3 -> 255;
                case 11 -> 50;
                case 15, 16, 17, 20 -> 100;
                case 21 -> 20;
                default -> 0;
            };
            if (limit == 0) continue;
            String value = switch (column) {
                case 3 -> cleanCompanyName(cell(row, column));
                case 11 -> normalizeOfficePhone(cell(row, column));
                default -> cell(row, column);
            };
            int length = value.codePointCount(0, value.length());
            if (length > limit) conflict(preview, "Kolom " + HEADERS.get(column) + " berisi "
                    + length + " karakter; maksimal " + limit + ". Perbaiki isi sel lalu upload ulang.");
        }
    }

    private int validateHeaders(Row row) {
        if (row == null) throw new IllegalArgumentException("Header Excel kosong.");
        int branchColumn = branchColumn(row);
        for (int column = 0; column < HEADERS.size(); column++) {
            if (!normalizeKey(HEADERS.get(column)).equals(normalizeKey(cell(row, column)))) {
                throw new IllegalArgumentException("Kolom " + (columnIndex(row, column) + 1) + " harus '" + HEADERS.get(column) + "'.");
            }
        }
        return branchColumn;
    }

    private boolean isBranchHeader(Cell header) {
        return Set.of("cabang/kantor", "branch", "branch name")
                .contains(normalizeKey(getCellValueAsString(header)));
    }

    private int branchColumn(Row headers) {
        int found = -1;
        for (Cell header : headers) {
            if (isBranchHeader(header)) {
                if (found >= 0) throw new IllegalArgumentException("Kolom Cabang/Kantor hanya boleh satu.");
                found = header.getColumnIndex();
            }
        }
        if (found >= 0 && found != 20 && found < HEADERS.size()) {
            throw new IllegalArgumentException("Kolom Cabang/Kantor harus setelah Linkedin Link atau di akhir template lama.");
        }
        return found;
    }

    private static List<String> emailTokens(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.toLowerCase(Locale.ROOT).split("[,;/\\s]+"))
                .filter(token -> !token.isBlank()).distinct().toList();
    }

    static List<String> splitEmailTokens(String raw) {
        return emailTokens(raw);
    }

    static boolean isPublicPersonalEmail(String email) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.indexOf("@") + 1).toLowerCase(Locale.ROOT).trim();
        Set<String> publicDomains = Set.of(
            "gmail.com", "googlemail.com",
            "yahoo.com", "yahoo.co.id", "yahoo.co.uk", "ymail.com", "rocketmail.com",
            "hotmail.com", "hotmail.co.id", "outlook.com", "outlook.co.id",
            "live.com", "live.co.id", "windowslive.com", "msn.com",
            "icloud.com", "me.com", "mac.com",
            "aol.com", "mail.com", "zoho.com", "proton.me", "protonmail.com"
        );
        return publicDomains.contains(domain);
    }

    static List<String> getCorporateEmailsInPersonalColumn(String personalEmail) {
        return splitEmailTokens(personalEmail).stream()
                .filter(email -> !isPublicPersonalEmail(email))
                .distinct()
                .toList();
    }

    private String normalizeOfficePhone(String value) {
        String phone = cleanPhone(value);
        // Keep lists/extensions intact; do not concatenate them into a different phone number.
        if (!phone.matches("[+()0-9\\s.-]+")) return phone;
        String normalized = formatNormalizedPhone(phone);
        return normalized == null ? "" : normalized.substring(1);
    }

    private String formatNormalizedPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.equals("62") || digits.equals("0")) return null;
        if (digits.startsWith("0")) {
            return "+62" + digits.substring(1);
        } else if (digits.startsWith("62")) {
            return "+" + digits;
        } else {
            return "+62" + digits;
        }
    }

    private List<String> getMissingMandatoryFields(
            String groupName, String brandName, String companyName,
            String salutation, String firstName, String lastName,
            String positionStr, String jobTitle, String address,
            String officePhone, String mobilePhone, String companyEmail,
            String industry, String city, String website) {
        List<String> missing = new ArrayList<>();
        if (groupName.isEmpty()) missing.add("Nama Group Holding");
        if (brandName.isEmpty()) missing.add("Nama Brand");
        if (companyName.isEmpty()) missing.add("Company Name");
        if (salutation.isEmpty()) missing.add("Salutation");
        if (firstName.isEmpty()) missing.add("First Name");
        if (lastName.isEmpty()) missing.add("Last Name");
        if (positionStr.isEmpty()) missing.add("Position");
        if (jobTitle.isEmpty()) missing.add("Job Title");
        if (address.isEmpty()) missing.add("Address");
        if (officePhone.isEmpty()) missing.add("Office Phone");
        if (mobilePhone.isEmpty()) missing.add("Mobile Phone");
        if (companyEmail.isEmpty()) missing.add("Company Email");
        if (industry.isEmpty()) missing.add("Industry");
        if (city.isEmpty()) missing.add("City");
        if (website.isEmpty()) missing.add("Company Website");
        return missing;
    }

    private String cleanCompanyName(String name) {
        if (name == null || name.isEmpty()) return "";
        String upper = name.toUpperCase();
        if (upper.startsWith("PT ") || upper.startsWith("PT. ")) {
            String base = name.substring(upper.startsWith("PT. ") ? 4 : 3).trim();
            if (base.endsWith(",")) {
                base = base.substring(0, base.length() - 1).trim();
            }
            return base + " PT";
        }
        if (upper.endsWith(" PT.")) {
            return name.substring(0, name.length() - 4).trim() + " PT";
        }
        return name;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = cell.getCachedFormulaResultType();
            } catch (Exception e) {
                return cell.getCellFormula();
            }
        }
        
        switch (type) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) || Math.abs(val - Math.round(val)) < 1e-9) {
                    return String.format(Locale.US, "%.0f", val);
                } else {
                    return String.valueOf(val);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                try {
                    return new DataFormatter().formatCellValue(cell).trim();
                } catch (Exception e) {
                    return "";
                }
        }
    }

    private String normalizeField(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.matches("^-+$")) return "";
        switch (trimmed.toLowerCase()) {
            case "n/a":
            case "na":
            case "none":
            case "null":
            case "tidak ada":
            case "kosong":
                return "";
            default:
                return trimmed;
        }
    }

    private String cleanPhone(String value) {
        String normalized = normalizeField(value);
        if (normalized.isEmpty()) return "";
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.equals("62") || digits.equals("0")) {
            return "";
        }
        return normalized;
    }

    private String safeFileName(String fileName) {
        return fileName == null || fileName.isBlank() ? "tanpa_nama.xlsx" : fileName.trim();
    }

    @lombok.Data
    @lombok.Builder
    public static class ImportPreviewResponse {
        private int totalRows;
        private int newCount;
        private int duplicateCount;
        private int incompleteCount;
        private int conflictCount;
        private List<RowPreview> rows;
    }

    @lombok.Data
    @lombok.Builder
    public static class RowPreview {
        private int rowNum;
        private String groupName;
        private String companyName;
        private String branchName;
        private String firstName;
        private String lastName;
        private String jobTitle;
        private String email;
        private String companyEmail;
        private String personalEmail;
        private String mobilePhone;
        private Long existingDatabaseId;
        private String status;
        private String message;
    }

}
