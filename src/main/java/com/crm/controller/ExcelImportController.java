package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/databases")
public class ExcelImportController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

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

        // Pass 1: Validate completeness and intra-file conflicts for all rows before DB insertions
        try (InputStream isCheck = file.getInputStream();
             Workbook wbCheck = new XSSFWorkbook(isCheck)) {
            Sheet sheetCheck = wbCheck.getSheetAt(0);
            int lastRowNumCheck = sheetCheck.getLastRowNum();
            List<String> validationErrors = new ArrayList<>();
            Map<String, List<EmailOwnerInfo>> emailOwnersInFile = new LinkedHashMap<>();

            for (int r = 1; r <= lastRowNumCheck; r++) {
                Row row = sheetCheck.getRow(r);
                if (row == null) continue;

                String firstName = normalizeField(getCellValueAsString(row.getCell(5)));
                String lastName = normalizeField(getCellValueAsString(row.getCell(6)));
                if (firstName.isEmpty() && lastName.isEmpty()) continue;

                String fullName = (firstName + " " + lastName).trim();
                String rowLabel = formatRowLabel(row);
                String groupName = normalizeField(getCellValueAsString(row.getCell(1)));
                String brandName = normalizeField(getCellValueAsString(row.getCell(2)));
                String companyName = cleanCompanyName(normalizeField(getCellValueAsString(row.getCell(3))));
                String salutation = normalizeField(getCellValueAsString(row.getCell(4)));
                String positionStr = normalizeField(getCellValueAsString(row.getCell(7)));
                String jobTitle = normalizeField(getCellValueAsString(row.getCell(9)));
                String address = normalizeField(getCellValueAsString(row.getCell(10)));
                String officePhone = cleanPhone(getCellValueAsString(row.getCell(11)));
                String mobilePhone = cleanPhone(getCellValueAsString(row.getCell(12)));
                String companyEmail = normalizeField(getCellValueAsString(row.getCell(13)));
                String personalEmail = normalizeField(getCellValueAsString(row.getCell(14)));
                String industry = normalizeField(getCellValueAsString(row.getCell(15)));
                String city = normalizeField(getCellValueAsString(row.getCell(20)));
                String website = normalizeField(getCellValueAsString(row.getCell(22)));

                List<String> missing = getMissingMandatoryFields(
                        groupName, brandName, companyName, salutation, firstName, lastName,
                        positionStr, jobTitle, address, officePhone, mobilePhone, companyEmail,
                        industry, city, website
                );

                if (!missing.isEmpty()) {
                    validationErrors.add(rowLabel + " (" + fullName + "): Kolom kosong [" + String.join(", ", missing) + "]");
                }

                List<String> corporateEmailsInPersonal = getCorporateEmailsInPersonalColumn(personalEmail);
                if (!corporateEmailsInPersonal.isEmpty()) {
                    validationErrors.add(rowLabel + " (" + fullName + "): Email kantor tidak boleh berada di kolom Personal Email ["
                            + String.join(", ", corporateEmailsInPersonal) + "]");
                }

                List<String> personalEmailConflicts = getPersonalEmailConflicts(personalEmail, firstName, lastName, companyName);
                if (!personalEmailConflicts.isEmpty()) {
                    validationErrors.add(rowLabel + " (" + fullName + "): Personal Email sudah dipakai kontak lain ["
                            + String.join(", ", personalEmailConflicts) + "]");
                }

                registerEmailsForRow(emailOwnersInFile, rowLabel, fullName, companyEmail, personalEmail, row.getRowNum() + 1);
            }

            appendIntraFileConflictErrors(validationErrors, emailOwnersInFile);

            if (!validationErrors.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Import ditolak karena terdapat " + validationErrors.size() + " data yang bermasalah pada file Excel Anda:\n- " + String.join("\n- ", validationErrors)
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to validate Excel file: " + e.getMessage()));
        }

        int successCount = 0;
        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            
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
                String officePhone = cleanPhone(getCellValueAsString(row.getCell(11)));
                String mobilePhone = cleanPhone(getCellValueAsString(row.getCell(12)));
                String companyEmail = normalizeField(getCellValueAsString(row.getCell(13)));
                String personalEmail = normalizeField(getCellValueAsString(row.getCell(14)));
                String industry = normalizeField(getCellValueAsString(row.getCell(15)));
                String sizeRevenue = normalizeField(getCellValueAsString(row.getCell(16)));
                String sizeEmployee = normalizeField(getCellValueAsString(row.getCell(17)));
                String hardware = normalizeField(getCellValueAsString(row.getCell(18)));
                String linkedinUrl = normalizeField(getCellValueAsString(row.getCell(19)));
                String city = normalizeField(getCellValueAsString(row.getCell(20)));
                String postalCode = normalizeField(getCellValueAsString(row.getCell(21)));
                String website = normalizeField(getCellValueAsString(row.getCell(22)));

                if (firstName.isEmpty() && lastName.isEmpty()) {
                    continue; // Skip blank rows
                }

                // 1. Resolve Group
                Group group = null;
                if (!groupName.isEmpty()) {
                    group = groupRepository.findByNameIgnoreCase(groupName).orElse(null);
                    if (group == null) {
                        group = Group.builder().name(groupName).build();
                        group = groupRepository.save(group);
                    }
                }

                // 2. Resolve Company
                Company company = null;
                if (!companyName.isEmpty()) {
                    company = companyRepository.findByNameIgnoreCase(companyName).orElse(null);
                    if (company == null) {
                        company = Company.builder()
                                .name(companyName)
                                .brandName(brandName.isEmpty() ? null : brandName)
                                .address(address.isEmpty() ? null : address)
                                .officePhone(officePhone.isEmpty() ? null : officePhone)
                                .website(website.isEmpty() ? null : website)
                                .industry(industry.isEmpty() ? null : industry)
                                .companySizeRevenue(sizeRevenue.isEmpty() ? null : sizeRevenue)
                                .companySizeEmployee(sizeEmployee.isEmpty() ? null : sizeEmployee)
                                .companyHardware(hardware.isEmpty() ? null : hardware)
                                .city(city.isEmpty() ? null : city)
                                .postalCode(postalCode.isEmpty() ? null : postalCode)
                                .group(group)
                                .build();
                        company = companyRepository.save(company);
                    } else {
                        if (!brandName.isEmpty()) company.setBrandName(brandName);
                        if (!address.isEmpty()) company.setAddress(address);
                        if (!officePhone.isEmpty()) company.setOfficePhone(officePhone);
                        if (!website.isEmpty()) company.setWebsite(website);
                        if (!industry.isEmpty()) company.setIndustry(industry);
                        if (!sizeRevenue.isEmpty()) company.setCompanySizeRevenue(sizeRevenue);
                        if (!sizeEmployee.isEmpty()) company.setCompanySizeEmployee(sizeEmployee);
                        if (!hardware.isEmpty()) company.setCompanyHardware(hardware);
                        if (!city.isEmpty()) company.setCity(city);
                        if (!postalCode.isEmpty()) company.setPostalCode(postalCode);
                        if (group != null) company.setGroup(group);
                        company = companyRepository.save(company);
                    }
                }

                // 3. Find existing Database record or create new
                Database targetDb = findExistingDatabase(firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);
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
                    targetDb.setIsActive(true);
                    targetDb = databaseRepository.save(targetDb);
                } else {
                    targetDb = Database.builder()
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
                            .build();
                    targetDb = databaseRepository.save(targetDb);
                }

                // 4. Save / Sync Emails Safely
                if (targetDb.getEmails() == null) {
                    targetDb.setEmails(new ArrayList<>());
                }
                Long targetDatabaseId = targetDb.getId();

                List<String> cTokens = splitEmailTokens(companyEmail);
                List<String> pTokens = splitEmailTokens(personalEmail);

                List<String> corporateEmails = new ArrayList<>();
                List<String> personalEmails = new ArrayList<>();

                // 1. Ekstrak kolom Personal Email: hanya email domain pribadi (Gmail, Outlook, Yahoo, dll). Email kantor ditolak!
                for (String token : pTokens) {
                    if (isPublicPersonalEmail(token)) {
                        if (!personalEmails.contains(token)) {
                            personalEmails.add(token);
                        }
                    }
                }

                // 2. Ekstrak kolom Company Email:
                List<String> corpFromComp = new ArrayList<>();
                List<String> pubFromComp = new ArrayList<>();

                for (String token : cTokens) {
                    if (isPublicPersonalEmail(token)) {
                        pubFromComp.add(token);
                    } else {
                        corpFromComp.add(token);
                    }
                }

                if (!corpFromComp.isEmpty()) {
                    // Ada email berdomain kantor:
                    for (String ct : corpFromComp) {
                        if (!corporateEmails.contains(ct)) {
                            corporateEmails.add(ct);
                        }
                    }
                    // Jika di kolom company juga tercampur email pribadi (misal Johanes/Eko Kusbiyanto):
                    // alokasikan email pribadi tersebut ke personalEmails
                    for (String pt : pubFromComp) {
                        if (!personalEmails.contains(pt)) {
                            personalEmails.add(pt);
                        }
                    }
                } else {
                    // Tidak ada email berdomain kantor sama sekali (misal perusahaan memakai Gmail seperti Edwin Sutedja):
                    if (!pubFromComp.isEmpty()) {
                        corporateEmails.add(pubFromComp.get(0));
                        for (int i = 1; i < pubFromComp.size(); i++) {
                            if (!personalEmails.contains(pubFromComp.get(i))) {
                                personalEmails.add(pubFromComp.get(i));
                            }
                        }
                    }
                }

                personalEmails.removeAll(corporateEmails);

                // Save Corporate Emails
                boolean firstCorp = true;
                for (String cEmail : corporateEmails) {
                    DatabaseEmail existingForContact = databaseEmailRepository.findAllByEmailIgnoreCase(cEmail).stream()
                            .filter(email -> email.getDatabase() != null && email.getDatabase().getId().equals(targetDatabaseId))
                            .findFirst()
                            .orElse(null);
                    if (existingForContact != null) {
                        existingForContact.setIsCorporate(true);
                        existingForContact.setIsPrimary(firstCorp);
                        existingForContact.setEmailType("company");
                        databaseEmailRepository.save(existingForContact);
                    } else {
                        DatabaseEmail emailObj = DatabaseEmail.builder()
                                .email(cEmail)
                                .emailType("company")
                                .isCorporate(true)
                                .isPrimary(firstCorp)
                                .database(targetDb)
                                .build();
                        databaseEmailRepository.save(emailObj);
                        targetDb.getEmails().add(emailObj);
                    }
                    firstCorp = false;
                }

                // Save Personal Emails
                boolean firstPers = true;
                for (String pEmail : personalEmails) {
                    List<DatabaseEmail> existingEmails = databaseEmailRepository.findAllByEmailIgnoreCase(pEmail);
                    DatabaseEmail existingForContact = existingEmails.stream()
                            .filter(email -> email.getDatabase() != null && email.getDatabase().getId().equals(targetDatabaseId))
                            .findFirst()
                            .orElse(null);
                    if (existingForContact != null) {
                        existingForContact.setIsCorporate(false);
                        existingForContact.setIsPrimary(firstPers);
                        existingForContact.setEmailType("personal");
                        databaseEmailRepository.save(existingForContact);
                    } else if (existingEmails.isEmpty()) {
                        DatabaseEmail emailObj = DatabaseEmail.builder()
                                .email(pEmail)
                                .emailType("personal")
                                .isCorporate(false)
                                .isPrimary(firstPers)
                                .database(targetDb)
                                .build();
                        databaseEmailRepository.save(emailObj);
                        targetDb.getEmails().add(emailObj);
                    } else {
                        throw new IllegalStateException("Personal email already belongs to another contact: " + pEmail);
                    }
                    firstPers = false;
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
                    "Import Excel '" + safeFileName(file.getOriginalFilename()) + "' berhasil memproses " + successCount + " data database"
            );

            return ResponseEntity.ok(Map.of(
                "message", "Excel data imported successfully",
                "count", successCount
            ));
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "message", "Failed to import Excel file: " + e.getMessage(),
                "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/preview")
    public ResponseEntity<?> previewImport(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            
            List<PreviewRowState> previewStates = new ArrayList<>();
            Map<String, List<EmailOwnerInfo>> emailOwnersInFile = new LinkedHashMap<>();
            int totalValid = 0;
            
            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String groupName = normalizeField(getCellValueAsString(row.getCell(1)));
                String brandName = normalizeField(getCellValueAsString(row.getCell(2)));
                String companyName = cleanCompanyName(normalizeField(getCellValueAsString(row.getCell(3))));
                String salutation = normalizeField(getCellValueAsString(row.getCell(4)));
                String firstName = normalizeField(getCellValueAsString(row.getCell(5)));
                String lastName = normalizeField(getCellValueAsString(row.getCell(6)));
                String positionStr = normalizeField(getCellValueAsString(row.getCell(7)));
                String jobTitle = normalizeField(getCellValueAsString(row.getCell(9)));
                String address = normalizeField(getCellValueAsString(row.getCell(10)));
                String officePhone = cleanPhone(getCellValueAsString(row.getCell(11)));
                String mobilePhone = cleanPhone(getCellValueAsString(row.getCell(12)));
                String companyEmail = normalizeField(getCellValueAsString(row.getCell(13)));
                String personalEmail = normalizeField(getCellValueAsString(row.getCell(14)));
                String industry = normalizeField(getCellValueAsString(row.getCell(15)));
                String city = normalizeField(getCellValueAsString(row.getCell(20)));
                String website = normalizeField(getCellValueAsString(row.getCell(22)));

                if (firstName.isEmpty() && lastName.isEmpty()) continue;
                
                totalValid++;
                String fullName = (firstName + " " + lastName).trim();
                String rowLabel = formatRowLabel(row);

                List<String> missing = getMissingMandatoryFields(
                        groupName, brandName, companyName, salutation, firstName, lastName,
                        positionStr, jobTitle, address, officePhone, mobilePhone, companyEmail,
                        industry, city, website
                );

                registerEmailsForRow(emailOwnersInFile, rowLabel, fullName, companyEmail, personalEmail, row.getRowNum() + 1);

                Database existingDatabase = findExistingDatabase(firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);

                boolean phoneShared = false;
                boolean phoneSameCompany = false;
                String sharedDatabaseName = "";
                if (!mobilePhone.isEmpty()) {
                    String normPhone = formatNormalizedPhone(mobilePhone);
                    Database otherPhoneDatabase = normPhone != null
                            ? databaseRepository.findByNormalizedPhone(normPhone).stream().findFirst().orElse(null)
                            : null;
                    if (otherPhoneDatabase == null) {
                        otherPhoneDatabase = databaseRepository.findByMobilePhone(mobilePhone).stream().findFirst().orElse(null);
                    }
                    if (otherPhoneDatabase != null && (existingDatabase == null || !existingDatabase.getId().equals(otherPhoneDatabase.getId()))) {
                        phoneShared = true;
                        String otherCompany = otherPhoneDatabase.getCompany() != null ? Objects.toString(otherPhoneDatabase.getCompany().getName(), "") : "";
                        phoneSameCompany = !companyName.isBlank() && otherCompany.trim().equalsIgnoreCase(companyName.trim());
                        sharedDatabaseName = otherPhoneDatabase.getFirstName() + " " + otherPhoneDatabase.getLastName()
                                + (!phoneSameCompany && !otherCompany.isBlank() ? " (" + otherCompany + ")" : "");
                    }
                }

                boolean emailShared = false;
                String sharedEmailDatabaseName = "";
                List<String> personalEmailConflicts = getPersonalEmailConflicts(personalEmail, firstName, lastName, companyName);
                if (!personalEmailConflicts.isEmpty()) {
                    emailShared = true;
                    sharedEmailDatabaseName = String.join(", ", personalEmailConflicts);
                }

                List<String> sharedCompanyEmails = getSharedCompanyEmailWarnings(companyEmail, firstName, lastName, companyName);

                List<String> corpEmailsInPersonal = getCorporateEmailsInPersonalColumn(personalEmail);

                previewStates.add(new PreviewRowState(
                        RowPreview.builder()
                        .rowNum(row.getRowNum() + 1)
                        .groupName(groupName)
                        .companyName(companyName)
                        .firstName(firstName)
                        .lastName(lastName)
                        .jobTitle(jobTitle)
                        .email(companyEmail.isEmpty() ? personalEmail : companyEmail)
                        .status("NEW")
                        .message("")
                        .build(),
                        missing,
                        existingDatabase != null,
                        phoneShared,
                        phoneSameCompany,
                        sharedDatabaseName,
                        emailShared,
                        sharedEmailDatabaseName,
                        sharedCompanyEmails,
                        corpEmailsInPersonal
                ));
            }

            Map<Integer, List<String>> intraFileConflictsByRow = buildIntraFileConflictMessages(emailOwnersInFile);
            List<RowPreview> previews = new ArrayList<>();
            int newCount = 0;
            int duplicateCount = 0;
            int incompleteCount = 0;
            int conflictCount = 0;

            for (PreviewRowState state : previewStates) {
                RowPreview preview = state.preview();
                List<String> messageParts = new ArrayList<>();
                String status = "NEW";

                if (!state.missing().isEmpty()) {
                    status = "INCOMPLETE";
                    messageParts.add("DITOLAK (Data Belum Lengkap). Kolom kosong: " + String.join(", ", state.missing()));
                    incompleteCount++;
                }

                List<String> conflictMessages = intraFileConflictsByRow.getOrDefault(preview.getRowNum(), List.of());
                if (!conflictMessages.isEmpty()) {
                    if ("NEW".equals(status)) {
                        status = "CONFLICT";
                    }
                    messageParts.addAll(conflictMessages);
                    conflictCount++;
                }

                if (state.existingDatabase()) {
                    if ("NEW".equals(status)) {
                        status = "DUPLICATE";
                    }
                    messageParts.add("Kontak sudah terdaftar di database. Data detail akan diperbarui (sinkron).");
                    duplicateCount++;
                }

                if (state.emailShared()) {
                    if ("NEW".equals(status) || "DUPLICATE".equals(status)) {
                        status = "CONFLICT";
                    }
                    messageParts.add("DITOLAK: Personal Email sudah dipakai kontak lain: " + state.sharedEmailDatabaseName() + ".");
                    conflictCount++;
                }

                if ("NEW".equals(status)) {
                    newCount++;
                    messageParts.add("Akan disimpan sebagai kontak baru di database.");
                }

                if (state.phoneShared()) {
                    if (state.phoneSameCompany()) {
                        messageParts.add("Peringatan: Nomor telepon sama dengan rekan sekantor '" + state.sharedDatabaseName() + "'.");
                    } else {
                        messageParts.add("Peringatan: Nomor telepon sama dengan database perusahaan lain '" + state.sharedDatabaseName() + "' (Kandidat Tikus / Duplikat Lintas Perusahaan).");
                    }
                }

                if (!state.sharedCompanyEmails().isEmpty()) {
                    messageParts.add("Info: Company email digunakan bersama dengan kontak lain: " + String.join(", ", state.sharedCompanyEmails()) + ".");
                }

                if (state.corpEmailsInPersonal() != null && !state.corpEmailsInPersonal().isEmpty()) {
                    status = "ERROR";
                    messageParts.add("DITOLAK: Email kantor tidak boleh berada di kolom Personal Email (" + String.join(", ", state.corpEmailsInPersonal()) + "). Gunakan email pribadi seperti Gmail, Yahoo, atau Outlook.");
                    conflictCount++;
                }

                preview.setStatus(status);
                preview.setMessage(String.join(" | ", messageParts));
                previews.add(preview);
            }
            
            return ResponseEntity.ok(ImportPreviewResponse.builder()
                    .totalRows(totalValid)
                    .newCount(newCount)
                    .duplicateCount(duplicateCount)
                    .incompleteCount(incompleteCount)
                    .conflictCount(conflictCount)
                    .rows(previews)
                    .build());
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "message", "Failed to parse Excel file",
                "error", e.getMessage()
            ));
        }
    }

    private Database findExistingDatabase(
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail) {

        // Personal email uniquely identifies a person; company email and phone may be shared.
        for (String email : splitEmailTokens(personalEmail)) {
            for (DatabaseEmail match : databaseEmailRepository.findAllByEmailIgnoreCase(email)) {
                if (match.getDatabase() != null && sameIdentity(match.getDatabase(), firstName, lastName, companyName)) {
                    return match.getDatabase();
                }
            }
        }

        // Same name within the same company is an update; shared phone/company email is not.
        if (firstName != null && !firstName.isBlank()) {
            List<Database> nameMatches;
            if (lastName != null && !lastName.isBlank()) {
                nameMatches = databaseRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName.trim(), lastName.trim());
            } else {
                nameMatches = databaseRepository.findByFirstNameIgnoreCase(firstName.trim()).stream()
                        .filter(d -> d.getLastName() == null || d.getLastName().isBlank())
                        .collect(Collectors.toList());
                if (nameMatches.isEmpty()) {
                    nameMatches = databaseRepository.findByFirstNameIgnoreCase(firstName.trim());
                }
            }

            return nameMatches.stream()
                    .filter(database -> sameIdentity(database, firstName, lastName, companyName))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    static List<String> splitEmailTokens(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String[] parts = raw.split("[,;/\\s]+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && trimmed.contains("@")) {
                list.add(trimmed);
            }
        }
        return list;
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

    private List<String> getSharedCompanyEmailWarnings(String companyEmail, String firstName, String lastName, String companyName) {
        return splitEmailTokens(companyEmail).stream()
                .flatMap(email -> databaseEmailRepository.findAllByEmailIgnoreCase(email).stream())
                .map(DatabaseEmail::getDatabase)
                .filter(Objects::nonNull)
                .filter(database -> !sameIdentity(database, firstName, lastName, companyName))
                .map(database -> {
                    String cName = database.getCompany() != null ? Objects.toString(database.getCompany().getName(), "") : "";
                    return Objects.toString(database.getFirstName(), "") + " "
                            + Objects.toString(database.getLastName(), "")
                            + (cName.isBlank() ? "" : " (" + cName + ")");
                })
                .distinct()
                .toList();
    }

    private List<String> getPersonalEmailConflicts(String personalEmail, String firstName, String lastName, String companyName) {
        return splitEmailTokens(personalEmail).stream()
                .flatMap(email -> databaseEmailRepository.findAllByEmailIgnoreCase(email).stream())
                .map(DatabaseEmail::getDatabase)
                .filter(Objects::nonNull)
                .filter(database -> !sameIdentity(database, firstName, lastName, companyName))
                .map(database -> Objects.toString(database.getFirstName(), "") + " "
                        + Objects.toString(database.getLastName(), "") + " (ID " + database.getId() + ")")
                .distinct()
                .toList();
    }

    private boolean sameIdentity(Database database, String firstName, String lastName, String companyName) {
        String databaseCompany = database.getCompany() != null ? Objects.toString(database.getCompany().getName(), "") : "";
        return Objects.toString(database.getFirstName(), "").trim().equalsIgnoreCase(Objects.toString(firstName, "").trim())
                && Objects.toString(database.getLastName(), "").trim().equalsIgnoreCase(Objects.toString(lastName, "").trim())
                && databaseCompany.trim().equalsIgnoreCase(Objects.toString(companyName, "").trim());
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

    private void registerEmailsForRow(
            Map<String, List<EmailOwnerInfo>> emailOwnersInFile,
            String rowLabel,
            String fullName,
            String companyEmail,
            String personalEmail,
            int excelRowNumber) {
        Set<String> emailsInRow = new LinkedHashSet<>();
        emailsInRow.addAll(splitEmailTokens(personalEmail));

        for (String email : emailsInRow) {
            emailOwnersInFile
                    .computeIfAbsent(email, key -> new ArrayList<>())
                    .add(new EmailOwnerInfo(rowLabel, fullName, excelRowNumber, email));
        }
    }

    private void appendIntraFileConflictErrors(
            List<String> validationErrors,
            Map<String, List<EmailOwnerInfo>> emailOwnersInFile) {
        Map<Integer, List<String>> conflictsByRow = buildIntraFileConflictMessages(emailOwnersInFile);
        List<Integer> sortedRows = new ArrayList<>(conflictsByRow.keySet());
        Collections.sort(sortedRows);
        for (Integer rowNumber : sortedRows) {
            validationErrors.addAll(conflictsByRow.get(rowNumber));
        }
    }

    private Map<Integer, List<String>> buildIntraFileConflictMessages(
            Map<String, List<EmailOwnerInfo>> emailOwnersInFile) {
        Map<Integer, List<String>> conflictsByRow = new LinkedHashMap<>();

        for (List<EmailOwnerInfo> owners : emailOwnersInFile.values()) {
            if (owners.size() < 2) {
                continue;
            }

            for (EmailOwnerInfo owner : owners) {
                List<String> others = owners.stream()
                        .filter(other -> other.excelRowNumber() != owner.excelRowNumber())
                        .map(other -> other.rowLabel() + " (" + other.fullName() + ")")
                        .toList();

                if (others.isEmpty()) {
                    continue;
                }

                String message = "Konflik Personal Email: Email '" + owner.email() + "' sama dengan " + String.join(", ", others) + ". Personal email tidak boleh dipakai 2 nama berbeda di Excel.";
                conflictsByRow
                        .computeIfAbsent(owner.excelRowNumber(), key -> new ArrayList<>())
                        .add(message);
            }
        }

        return conflictsByRow;
    }

    private String formatRowLabel(Row row) {
        int excelRowNumber = row.getRowNum() + 1;
        String sequenceNumber = normalizeField(getCellValueAsString(row.getCell(0)));
        if (!sequenceNumber.isEmpty()) {
            return "Baris " + sequenceNumber + " (row Excel " + excelRowNumber + ")";
        }
        return "Baris Excel " + excelRowNumber;
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
        private String firstName;
        private String lastName;
        private String jobTitle;
        private String email;
        private String status;
        private String message;
    }

    private record EmailOwnerInfo(String rowLabel, String fullName, int excelRowNumber, String email) {}

    private record PreviewRowState(
            RowPreview preview,
            List<String> missing,
            boolean existingDatabase,
            boolean phoneShared,
            boolean phoneSameCompany,
            String sharedDatabaseName,
            boolean emailShared,
            String sharedEmailDatabaseName,
            List<String> sharedCompanyEmails,
            List<String> corpEmailsInPersonal) {}
}
