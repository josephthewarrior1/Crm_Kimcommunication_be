package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    @Autowired
    private com.crm.service.SuspiciousIdentityService suspiciousIdentityService;

    @Autowired
    private com.crm.service.SecurityHelper securityHelper;

    @PostMapping("/import")
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
                Database targetDb = null;
                boolean matchedByPhone = false;
                List<Database> nameMatches = databaseRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName, lastName);
                
                for (Database c : nameMatches) {
                    boolean companyMatch = (company == null && c.getCompany() == null) || 
                                           (company != null && c.getCompany() != null && c.getCompany().getId().equals(company.getId()));
                    
                    boolean phoneMatch = false;
                    if (!mobilePhone.isEmpty()) {
                        String normPhone = "+62" + mobilePhone.replaceAll("^0", "");
                        phoneMatch = (c.getNormalizedPhone() != null && c.getNormalizedPhone().equals(normPhone)) ||
                                     (c.getMobilePhone() != null && c.getMobilePhone().equals(mobilePhone));
                    }
                    
                    boolean emailMatch = false;
                    if (!companyEmail.isEmpty() || !personalEmail.isEmpty()) {
                        List<DatabaseEmail> cEmails = c.getEmails();
                        if (cEmails != null) {
                            for (DatabaseEmail ce : cEmails) {
                                if (!companyEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(companyEmail)) {
                                    emailMatch = true;
                                }
                                if (!personalEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(personalEmail)) {
                                    emailMatch = true;
                                }
                            }
                        }
                    }
                    
                    if (companyMatch || phoneMatch || emailMatch) {
                        targetDb = c;
                        if (phoneMatch) matchedByPhone = true;
                        break;
                    }
                }

                if (targetDb == null && !nameMatches.isEmpty() && mobilePhone.isEmpty() && companyEmail.isEmpty() && personalEmail.isEmpty()) {
                    targetDb = nameMatches.get(0);
                }

                PositionLevel posLevel = PositionLevel.fromValue(positionStr);

                if (targetDb != null) {
                    if (!salutation.isEmpty()) targetDb.setSalutation(salutation);
                    if (!positionStr.isEmpty()) targetDb.setPositionLevel(posLevel);
                    if (!specialityDivision.isEmpty()) targetDb.setSpecialityDivision(specialityDivision);
                    if (!jobTitle.isEmpty()) targetDb.setJobTitle(jobTitle);
                    if (!mobilePhone.isEmpty()) targetDb.setMobilePhone(mobilePhone);
                    if (!linkedinUrl.isEmpty()) targetDb.setLinkedinUrl(linkedinUrl);
                    if (company != null) targetDb.setCompany(company);
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
                            .linkedinUrl(linkedinUrl.isEmpty() ? null : linkedinUrl)
                            .isActive(true)
                            .company(company)
                            .build();
                    targetDb = databaseRepository.save(targetDb);
                }

                // 4. Save / Sync Emails Safely (Protecting unique constraints)
                if (targetDb.getEmails() == null) {
                    targetDb.setEmails(new ArrayList<>());
                }

                if (!companyEmail.isEmpty()) {
                    final String cEmailLower = companyEmail.toLowerCase();
                    DatabaseEmail existingDbEmail = databaseEmailRepository.findByEmail(cEmailLower).orElse(null);
                    
                    if (existingDbEmail == null) {
                        boolean alreadyInList = targetDb.getEmails().stream()
                                .anyMatch(e -> e.getEmail().equalsIgnoreCase(cEmailLower));
                        if (!alreadyInList) {
                            DatabaseEmail emailObj = DatabaseEmail.builder()
                                    .email(companyEmail)
                                    .emailType("company")
                                    .isCorporate(true)
                                    .database(targetDb)
                                    .build();
                            databaseEmailRepository.save(emailObj);
                            targetDb.getEmails().add(emailObj);
                        }
                    }
                }

                if (!personalEmail.isEmpty()) {
                    final String pEmailLower = personalEmail.toLowerCase();
                    DatabaseEmail existingDbEmail = databaseEmailRepository.findByEmail(pEmailLower).orElse(null);
                    
                    if (existingDbEmail == null) {
                        boolean alreadyInList = targetDb.getEmails().stream()
                                .anyMatch(e -> e.getEmail().equalsIgnoreCase(pEmailLower));
                        if (!alreadyInList) {
                            DatabaseEmail emailObj = DatabaseEmail.builder()
                                    .email(personalEmail)
                                    .emailType("personal")
                                    .isCorporate(false)
                                    .database(targetDb)
                                    .build();
                            databaseEmailRepository.save(emailObj);
                            targetDb.getEmails().add(emailObj);
                        }
                    }
                }

                suspiciousIdentityService.checkAndFlagDatabase(targetDb);
                successCount++;
            }

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

                Company company = null;
                if (!companyName.isEmpty()) {
                    company = companyRepository.findByNameIgnoreCase(companyName).orElse(null);
                }

                Database existingDatabase = null;
                boolean matchedByPhone = false;
                List<Database> nameMatches = databaseRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName, lastName);
                for (Database c : nameMatches) {
                    boolean companyMatch = (company == null && c.getCompany() == null) || 
                                           (company != null && c.getCompany() != null && c.getCompany().getId().equals(company.getId()));
                    
                    boolean phoneMatch = false;
                    if (!mobilePhone.isEmpty()) {
                        String normPhone = "+62" + mobilePhone.replaceAll("^0", "");
                        phoneMatch = (c.getNormalizedPhone() != null && c.getNormalizedPhone().equals(normPhone)) ||
                                     (c.getMobilePhone() != null && c.getMobilePhone().equals(mobilePhone));
                    }
                    
                    boolean emailMatch = false;
                    if (!companyEmail.isEmpty() || !personalEmail.isEmpty()) {
                        List<DatabaseEmail> cEmails = c.getEmails();
                        if (cEmails != null) {
                            for (DatabaseEmail ce : cEmails) {
                                if (!companyEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(companyEmail)) {
                                    emailMatch = true;
                                }
                                if (!personalEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(personalEmail)) {
                                    emailMatch = true;
                                }
                            }
                        }
                    }
                    
                    if (companyMatch || phoneMatch || emailMatch) {
                        existingDatabase = c;
                        if (phoneMatch) matchedByPhone = true;
                        break;
                    }
                }

                if (existingDatabase == null && !nameMatches.isEmpty() && mobilePhone.isEmpty() && companyEmail.isEmpty() && personalEmail.isEmpty()) {
                    existingDatabase = nameMatches.get(0);
                }

                boolean phoneShared = false;
                String sharedDatabaseName = "";
                if (!mobilePhone.isEmpty()) {
                    String normPhone = "+62" + mobilePhone.replaceAll("^0", "");
                    Database otherPhoneDatabase = databaseRepository.findByNormalizedPhone(normPhone).stream().findFirst().orElse(null);
                    if (otherPhoneDatabase == null) {
                        otherPhoneDatabase = databaseRepository.findByMobilePhone(mobilePhone).stream().findFirst().orElse(null);
                    }
                    if (otherPhoneDatabase != null && (existingDatabase == null || !existingDatabase.getId().equals(otherPhoneDatabase.getId()))) {
                        phoneShared = true;
                        sharedDatabaseName = otherPhoneDatabase.getFirstName() + " " + otherPhoneDatabase.getLastName();
                    }
                }

                boolean emailShared = false;
                String sharedEmailDatabaseName = "";
                if (!companyEmail.isEmpty() || !personalEmail.isEmpty()) {
                    String emailToCheck = companyEmail.isEmpty() ? personalEmail : companyEmail;
                    DatabaseEmail otherEmailRecord = databaseEmailRepository.findByEmail(emailToCheck.toLowerCase()).stream().findFirst().orElse(null);
                    if (otherEmailRecord != null && otherEmailRecord.getDatabase() != null) {
                        Database other = otherEmailRecord.getDatabase();
                        if (existingDatabase == null || !existingDatabase.getId().equals(other.getId())) {
                            emailShared = true;
                            sharedEmailDatabaseName = other.getFirstName() + " " + other.getLastName();
                        }
                    }
                }

                boolean emailDuplicate = false;
                String duplicateMsg = "";
                if (!companyEmail.isEmpty()) {
                    if (databaseEmailRepository.findByEmail(companyEmail.toLowerCase()).isPresent()) {
                        emailDuplicate = true;
                        duplicateMsg = "Company email already exists";
                    }
                }
                if (!personalEmail.isEmpty()) {
                    if (databaseEmailRepository.findByEmail(personalEmail.toLowerCase()).isPresent()) {
                        emailDuplicate = true;
                        duplicateMsg = duplicateMsg.isEmpty() ? "Personal email already exists" : "Both emails already exist";
                    }
                }

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
                        matchedByPhone,
                        emailDuplicate,
                        duplicateMsg,
                        phoneShared,
                        sharedDatabaseName,
                        emailShared,
                        sharedEmailDatabaseName
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
                    messageParts.add(state.matchedByPhone()
                        ? "Database record already exists (phone matched). Details will be updated."
                        : "Database record already exists. Details will be updated.");
                    duplicateCount++;
                }

                if (state.emailDuplicate() && !state.emailShared()) {
                    if ("NEW".equals(status)) {
                        status = "DUPLICATE";
                    }
                    if (!state.existingDatabase()) {
                        duplicateCount++;
                    }
                    messageParts.add("Email duplicate: " + state.duplicateMsg() + ". Details will be updated.");
                }

                if ("NEW".equals(status)) {
                    newCount++;
                    messageParts.add("Will be created as a new database record");
                    if (state.phoneShared()) {
                        messageParts.add("Warning: Phone number is identical to database record '" + state.sharedDatabaseName() + "' (Tikus candidate).");
                    }
                    if (state.emailShared()) {
                        messageParts.add("Warning: Email is identical to database record '" + state.sharedEmailDatabaseName() + "' (Tikus candidate).");
                    }
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
        if (!companyEmail.isEmpty()) {
            emailsInRow.add(companyEmail.toLowerCase(Locale.ROOT));
        }
        if (!personalEmail.isEmpty()) {
            emailsInRow.add(personalEmail.toLowerCase(Locale.ROOT));
        }

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

                String message = "Konflik Email: Email '" + owner.email() + "' sama dengan " + String.join(", ", others) + ". Satu email tidak boleh dipakai 2 nama berbeda di Excel.";
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
            boolean matchedByPhone,
            boolean emailDuplicate,
            String duplicateMsg,
            boolean phoneShared,
            String sharedDatabaseName,
            boolean emailShared,
            String sharedEmailDatabaseName) {}
}
