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
@RequestMapping("/api/contacts")
public class ExcelImportController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ContactEmailRepository contactEmailRepository;

    @PostMapping("/import")
    public ResponseEntity<?> importContacts(@RequestParam("file") MultipartFile file) {
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
                String groupName = getCellValueAsString(row.getCell(1)).trim();
                String brandName = getCellValueAsString(row.getCell(2)).trim();
                String companyName = cleanCompanyName(getCellValueAsString(row.getCell(3)).trim());
                String salutation = getCellValueAsString(row.getCell(4)).trim();
                String firstName = getCellValueAsString(row.getCell(5)).trim();
                String lastName = getCellValueAsString(row.getCell(6)).trim();
                String positionStr = getCellValueAsString(row.getCell(7)).trim();
                String specialityDivision = getCellValueAsString(row.getCell(8)).trim();
                String jobTitle = getCellValueAsString(row.getCell(9)).trim();
                String address = getCellValueAsString(row.getCell(10)).trim();
                String officePhone = getCellValueAsString(row.getCell(11)).trim();
                String mobilePhone = getCellValueAsString(row.getCell(12)).trim();
                String companyEmail = getCellValueAsString(row.getCell(13)).trim();
                String personalEmail = getCellValueAsString(row.getCell(14)).trim();
                String industry = getCellValueAsString(row.getCell(15)).trim();
                String sizeRevenue = getCellValueAsString(row.getCell(16)).trim();
                String sizeEmployee = getCellValueAsString(row.getCell(17)).trim();
                String hardware = getCellValueAsString(row.getCell(18)).trim();
                String linkedinUrl = getCellValueAsString(row.getCell(19)).trim();
                String city = getCellValueAsString(row.getCell(20)).trim();
                String website = getCellValueAsString(row.getCell(21)).trim();

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
                        if (group != null) company.setGroup(group);
                        company = companyRepository.save(company);
                    }
                }

                // 3. Resolve Contact
                Contact contact = null;
                // Find matches by name
                List<Contact> matches = contactRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName, lastName);
                final Company finalCompany = company;
                contact = matches.stream()
                        .filter(c -> (finalCompany == null && c.getCompany() == null) || 
                                     (finalCompany != null && c.getCompany() != null && c.getCompany().getId().equals(finalCompany.getId())))
                        .findFirst()
                        .orElse(null);

                if (contact == null) {
                    contact = Contact.builder()
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
                            .contactType(ContactType.unknown)
                            .source(ContactSource.excel_import)
                            .isActive(true)
                            .build();
                    contact = contactRepository.save(contact);
                } else {
                    // Update details
                    if (!salutation.isEmpty()) contact.setSalutation(salutation);
                    contact.setPositionLevel(PositionLevel.fromValue(positionStr));
                    if (!specialityDivision.isEmpty()) contact.setSpecialityDivision(specialityDivision);
                    if (!jobTitle.isEmpty()) contact.setJobTitle(jobTitle);
                    if (!mobilePhone.isEmpty()) {
                        contact.setMobilePhone(mobilePhone);
                        contact.setNormalizedPhone("+62" + mobilePhone.replaceAll("^0", ""));
                    }
                    if (!linkedinUrl.isEmpty()) contact.setLinkedinUrl(linkedinUrl);
                    if (company != null) contact.setCompany(company);
                    contact = contactRepository.save(contact);
                }

                // 4. Resolve Emails
                if (contact != null) {
                    if (!companyEmail.isEmpty()) {
                        String cleanEmail = companyEmail.toLowerCase();
                        ContactEmail ce = contactEmailRepository.findByEmail(cleanEmail).orElse(null);
                        if (ce == null) {
                            String domain = cleanEmail.substring(cleanEmail.indexOf("@") + 1);
                            ce = ContactEmail.builder()
                                    .contact(contact)
                                    .email(cleanEmail)
                                    .emailType("company")
                                    .isPrimary(true)
                                    .isVerified(true)
                                    .isCorporate(true)
                                    .domain(domain)
                                    .build();
                            contactEmailRepository.save(ce);
                        } else {
                            ce.setContact(contact);
                            contactEmailRepository.save(ce);
                        }
                    }

                    if (!personalEmail.isEmpty()) {
                        String cleanEmail = personalEmail.toLowerCase();
                        ContactEmail pe = contactEmailRepository.findByEmail(cleanEmail).orElse(null);
                        if (pe == null) {
                            String domain = cleanEmail.substring(cleanEmail.indexOf("@") + 1);
                            pe = ContactEmail.builder()
                                    .contact(contact)
                                    .email(cleanEmail)
                                    .emailType("personal")
                                    .isPrimary(false)
                                    .isVerified(true)
                                    .isCorporate(false)
                                    .domain(domain)
                                    .build();
                            contactEmailRepository.save(pe);
                        } else {
                            pe.setContact(contact);
                            contactEmailRepository.save(pe);
                        }
                    }
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
                // Format large numbers (like phone numbers) as clean integers if they have no decimal part
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
}
