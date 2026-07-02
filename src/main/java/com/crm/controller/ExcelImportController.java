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

                // Get values from row columns
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

                // Validate minimum requirements: First Name and Last Name
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
                        // Update details
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

                // 3. Resolve Database Record (Must match name to be a duplicate/update target)
                Database database = null;
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
                        final Long databaseId = c.getId();
                        List<DatabaseEmail> cEmails = databaseEmailRepository.findAll().stream()
                            .filter(e -> e.getDatabase() != null && e.getDatabase().getId().equals(databaseId))
                            .toList();
                        for (DatabaseEmail ce : cEmails) {
                            if (!companyEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(companyEmail)) {
                                emailMatch = true;
                            }
                            if (!personalEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(personalEmail)) {
                                emailMatch = true;
                            }
                        }
                    }
                    
                    if (companyMatch || phoneMatch || emailMatch) {
                        database = c;
                        break;
                    }
                }

                if (database == null && !nameMatches.isEmpty() && mobilePhone.isEmpty() && companyEmail.isEmpty() && personalEmail.isEmpty()) {
                    database = nameMatches.get(0);
                }

                if (database == null) {
                    database = Database.builder()
                            .salutation(salutation.isEmpty() ? "Mr" : salutation)
                            .firstName(firstName)
                            .lastName(lastName)
                            .positionLevel(PositionLevel.fromValue(positionStr))
                            .specialityDivision(specialityDivision.isEmpty() ? null : specialityDivision)
                            .jobTitle(jobTitle.isEmpty() ? null : jobTitle)
                            .mobilePhone(mobilePhone.isEmpty() ? null : mobilePhone)
                            .normalizedPhone(mobilePhone.isEmpty() ? null : "+62" + mobilePhone.replaceAll("^0", ""))
                            .linkedinUrl(linkedinUrl.isEmpty() ? null : linkedinUrl)
                            .company(company)
                            .databaseType(DatabaseType.unknown)
                            .source(DatabaseSource.excel_import)
                            .isActive(true)
                            .build();
                    database = databaseRepository.save(database);
                } else {
                    // Update details
                    if (!salutation.isEmpty()) database.setSalutation(salutation);
                    database.setPositionLevel(PositionLevel.fromValue(positionStr));
                    if (!specialityDivision.isEmpty()) database.setSpecialityDivision(specialityDivision);
                    if (!jobTitle.isEmpty()) database.setJobTitle(jobTitle);
                    if (!mobilePhone.isEmpty()) {
                        database.setMobilePhone(mobilePhone);
                        database.setNormalizedPhone("+62" + mobilePhone.replaceAll("^0", ""));
                    }
                    if (!linkedinUrl.isEmpty()) database.setLinkedinUrl(linkedinUrl);
                    if (company != null) database.setCompany(company);
                    database = databaseRepository.save(database);
                }

                // 4. Resolve Emails
                if (database != null) {
                    if (!companyEmail.isEmpty()) {
                        String cleanEmail = companyEmail.toLowerCase();
                        DatabaseEmail ce = databaseEmailRepository.findByEmail(cleanEmail).orElse(null);
                        if (ce == null) {
                            String domain = cleanEmail.substring(cleanEmail.indexOf("@") + 1);
                            ce = DatabaseEmail.builder()
                                    .database(database)
                                    .email(cleanEmail)
                                    .emailType("company")
                                    .isPrimary(true)
                                    .isVerified(true)
                                    .isCorporate(true)
                                    .domain(domain)
                                    .build();
                            databaseEmailRepository.save(ce);
                        } else {
                            ce.setDatabase(database);
                            databaseEmailRepository.save(ce);
                        }
                    }

                    if (!personalEmail.isEmpty()) {
                        String cleanEmail = personalEmail.toLowerCase();
                        DatabaseEmail pe = databaseEmailRepository.findByEmail(cleanEmail).orElse(null);
                        if (pe == null) {
                            String domain = cleanEmail.substring(cleanEmail.indexOf("@") + 1);
                            pe = DatabaseEmail.builder()
                                    .database(database)
                                    .email(cleanEmail)
                                    .emailType("personal")
                                    .isPrimary(false)
                                    .isVerified(true)
                                    .isCorporate(false)
                                    .domain(domain)
                                    .build();
                            databaseEmailRepository.save(pe);
                        } else {
                            pe.setDatabase(database);
                            databaseEmailRepository.save(pe);
                        }
                    }
                    suspiciousIdentityService.checkAndFlagDatabase(database);
                }
                successCount++;
            }
            
            return ResponseEntity.ok(Map.of(
                "message", "Excel data imported successfully",
                "count", successCount
            ));
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "message", "Failed to parse Excel file",
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
            
            List<RowPreview> previews = new ArrayList<>();
            int newCount = 0;
            int duplicateCount = 0;
            int totalValid = 0;
            
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
                String jobTitle = normalizeField(getCellValueAsString(row.getCell(9)));
                String companyEmail = normalizeField(getCellValueAsString(row.getCell(13)));
                String personalEmail = normalizeField(getCellValueAsString(row.getCell(14)));
                String mobilePhone = cleanPhone(getCellValueAsString(row.getCell(12)));

                // Validate minimum requirements: First Name and Last Name
                if (firstName.isEmpty() && lastName.isEmpty()) {
                    continue; // Skip blank rows
                }
                
                totalValid++;

                // Check duplicate database record in DB
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
                        final Long databaseId = c.getId();
                        List<DatabaseEmail> cEmails = databaseEmailRepository.findAll().stream()
                            .filter(e -> e.getDatabase() != null && e.getDatabase().getId().equals(databaseId))
                            .toList();
                        for (DatabaseEmail ce : cEmails) {
                            if (!companyEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(companyEmail)) {
                                emailMatch = true;
                            }
                            if (!personalEmail.isEmpty() && ce.getEmail().equalsIgnoreCase(personalEmail)) {
                                emailMatch = true;
                            }
                        }
                    }
                    
                    if (companyMatch || phoneMatch || emailMatch) {
                        existingDatabase = c;
                        if (phoneMatch) {
                            matchedByPhone = true;
                        }
                        break;
                    }
                }

                if (existingDatabase == null && !nameMatches.isEmpty() && mobilePhone.isEmpty() && companyEmail.isEmpty() && personalEmail.isEmpty()) {
                    existingDatabase = nameMatches.get(0);
                }

                // Check if phone or email is shared/used by a DIFFERENT database record name (tikus warning candidate)
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

                // Also check if emails are already used
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

                String status = "NEW";
                String message = "Will be created as a new database record";
                
                if (existingDatabase != null) {
                    status = "DUPLICATE";
                    message = matchedByPhone 
                        ? "Database record already exists (phone matched). Details will be updated."
                        : "Database record already exists. Details will be updated.";
                    duplicateCount++;
                } else if (emailDuplicate && !emailShared) {
                    status = "DUPLICATE";
                    message = "Email duplicate: " + duplicateMsg + ". Details will be updated.";
                    duplicateCount++;
                } else {
                    newCount++;
                    if (phoneShared) {
                        message = "Will be created. Warning: Phone number is identical to database record '" + sharedDatabaseName + "' (Tikus candidate).";
                    } else if (emailShared) {
                        message = "Will be created. Warning: Email is identical to database record '" + sharedEmailDatabaseName + "' (Tikus candidate).";
                    }
                }

                previews.add(RowPreview.builder()
                        .rowNum(r)
                        .groupName(groupName)
                        .companyName(companyName)
                        .firstName(firstName)
                        .lastName(lastName)
                        .jobTitle(jobTitle)
                        .email(companyEmail.isEmpty() ? personalEmail : companyEmail)
                        .status(status)
                        .message(message)
                        .build());
            }
            
            return ResponseEntity.ok(ImportPreviewResponse.builder()
                    .totalRows(totalValid)
                    .newCount(newCount)
                    .duplicateCount(duplicateCount)
                    .rows(previews)
                    .build());
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "message", "Failed to parse Excel file",
                "error", e.getMessage()
            ));
        }
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

    private String cleanCompanyName(String name) {
        if (name == null) return "";
        name = name.trim();
        String upper = name.toUpperCase();
        if (upper.endsWith(" PT") || upper.endsWith(" PT.")) {
            String base = name.substring(0, name.length() - (upper.endsWith(" PT.") ? 4 : 3)).trim();
            if (base.endsWith(",")) {
                base = base.substring(0, base.length() - 1).trim();
            }
            return "PT " + base;
        }
        return name;
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

    @lombok.Data
    @lombok.Builder
    public static class ImportPreviewResponse {
        private int totalRows;
        private int newCount;
        private int duplicateCount;
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
}
