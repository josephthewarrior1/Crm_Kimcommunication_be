package com.crm.service;

import com.crm.domain.Company;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.Group;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.GroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataCleanerService {

    private static final Set<String> PERSONAL_DOMAINS = Set.of(
            "gmail.com", "yahoo.com", "yahoo.co.id", "outlook.com", "hotmail.com",
            "icloud.com", "live.com", "ymail.com", "rocketmail.com", "aol.com"
    );
    private static final Map<String, String> CANONICAL_GROUP_KEYS = Map.ofEntries(
            Map.entry("astrainternational", "astra-international"),
            Map.entry("astrainternasional", "astra-international"),
            Map.entry("astrainternationaltbk", "astra-international"),
            Map.entry("astrainternasionaltbk", "astra-international"),
            Map.entry("bankcentralasia", "bca"),
            Map.entry("bca", "bca"),
            Map.entry("bankrakyatindonesia", "bri"),
            Map.entry("bankrakyatindonesiapersero", "bri"),
            Map.entry("bri", "bri"),
            Map.entry("banknegaraindonesia", "bni"),
            Map.entry("bni", "bni"),
            Map.entry("bankmandiri", "mandiri"),
            Map.entry("mandiri", "mandiri"),
            Map.entry("ctcorp", "ct-corp"),
            Map.entry("ctcorpora", "ct-corp"),
            Map.entry("dbs", "dbs"),
            Map.entry("dbsbankltd", "dbs"),
            Map.entry("dbsgroupholdingsltd", "dbs"),
            Map.entry("indonesiafinancial", "ifg"),
            Map.entry("indonesiafinancialifg", "ifg"),
            Map.entry("msadinsurance", "ms-ad"),
            Map.entry("msadinsuranceholdings", "ms-ad"),
            Map.entry("anz", "anz"),
            Map.entry("anzholdings", "anz"),
            Map.entry("mufg", "mufg"),
            Map.entry("mitsubishiufjfinancialmufg", "mufg"),
            Map.entry("smbc", "smbc"),
            Map.entry("smbcsumitomomitsui", "smbc"),
            Map.entry("sumitomomitsuifinancialsmfg", "smbc"),
            Map.entry("ptpniii", "ptpn"),
            Map.entry("perkebunannusantaraiii", "ptpn"),
            Map.entry("holdingperkebunannusantaraptpn", "ptpn"),
            Map.entry("sampoernastrategic", "sampoerna-strategic"),
            Map.entry("sampoernastrategicgroup", "sampoerna-strategic")
    );

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    public Map<String, Object> preview() {
        return clean(false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> groupAudit(boolean onlyIssues) {
        List<Group> groups = groupRepository.findAll().stream()
                .sorted((left, right) -> cleanText(left.getName()).compareToIgnoreCase(cleanText(right.getName())))
                .toList();
        Map<Long, List<Company>> companiesByGroupId = companyRepository.findAll().stream()
                .collect(Collectors.groupingBy(company -> company.getGroup() != null ? company.getGroup().getId() : -1L));

        List<Map<String, Object>> groupRows = new ArrayList<>();
        int totalCompanies = 0;
        int flaggedCompanies = 0;

        for (Group group : groups) {
            List<Company> companies = companiesByGroupId.getOrDefault(group.getId(), List.of()).stream()
                    .sorted((left, right) -> cleanText(left.getName()).compareToIgnoreCase(cleanText(right.getName())))
                    .toList();
            List<Map<String, Object>> companyRows = new ArrayList<>();
            int groupIssueCount = 0;

            for (Company company : companies) {
                Map<String, Object> row = auditCompany(company);
                @SuppressWarnings("unchecked")
                List<String> issues = (List<String>) row.get("issues");
                if (!issues.isEmpty()) {
                    groupIssueCount++;
                    flaggedCompanies++;
                }
                if (!onlyIssues || !issues.isEmpty()) {
                    companyRows.add(row);
                }
            }
            if (onlyIssues && companyRows.isEmpty()) {
                continue;
            }

            Map<String, Object> groupRow = new LinkedHashMap<>();
            groupRow.put("id", group.getId());
            groupRow.put("name", group.getName());
            groupRow.put("notes", group.getNotes());
            groupRow.put("companyCount", companies.size());
            groupRow.put("issueCount", groupIssueCount);
            groupRow.put("status", groupIssueCount == 0 ? "OK" : "REVIEW");
            groupRow.put("companies", companyRows);
            groupRows.add(groupRow);
            totalCompanies += companies.size();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalGroups", groups.size());
        result.put("totalCompanies", totalCompanies);
        result.put("flaggedCompanies", flaggedCompanies);
        result.put("groups", groupRows);
        return result;
    }

    @Transactional
    public Map<String, Object> apply() {
        return clean(true);
    }

    @Transactional
    public Map<String, Object> mergeGroups(Long targetGroupId, List<Long> sourceGroupIds) {
        Group target = groupRepository.findById(targetGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Target group not found"));
        List<Long> cleanSourceIds = sourceGroupIds == null ? List.of() : sourceGroupIds.stream()
                .filter(id -> id != null && !id.equals(targetGroupId))
                .distinct()
                .toList();
        if (cleanSourceIds.isEmpty()) {
            throw new IllegalArgumentException("Source groups are required");
        }

        List<Group> sources = groupRepository.findAllById(cleanSourceIds);
        if (sources.size() != cleanSourceIds.size()) {
            throw new IllegalArgumentException("Some source groups were not found");
        }

        int movedCompanies = 0;
        for (Company company : companyRepository.findAll()) {
            if (company.getGroup() != null && cleanSourceIds.contains(company.getGroup().getId())) {
                company.setGroup(target);
                companyRepository.save(company);
                movedCompanies++;
            }
        }
        groupRepository.deleteAll(sources);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetGroupId", target.getId());
        result.put("targetGroupName", target.getName());
        result.put("deletedGroupIds", cleanSourceIds);
        result.put("movedCompanies", movedCompanies);
        return result;
    }

    private Map<String, Object> clean(boolean apply) {
        List<Map<String, Object>> fixes = new ArrayList<>();
        List<Map<String, Object>> reviews = new ArrayList<>();

        List<Group> groups = groupRepository.findAll();
        List<Company> companies = companyRepository.findAll();
        List<DatabaseEmail> emails = databaseEmailRepository.findAll();
        Map<String, List<DatabaseEmail>> emailsByCleanValue = emails.stream()
                .filter(email -> !cleanEmail(email.getEmail()).isBlank())
                .collect(Collectors.groupingBy(email -> cleanEmail(email.getEmail())));

        for (Group group : groups) {
            String cleaned = cleanText(group.getName());
            if (!same(group.getName(), cleaned)) {
                fixes.add(change("group_name_trim", group.getId(), group.getName(), cleaned));
                if (apply) group.setName(cleaned);
            }
        }

        for (Company company : companies) {
            cleanCompanyField(fixes, apply, company, "company_name_trim", company.getName(), company::setName);
            cleanCompanyField(fixes, apply, company, "brand_name_trim", company.getBrandName(), company::setBrandName);
            cleanCompanyField(fixes, apply, company, "industry_trim", company.getIndustry(), company::setIndustry);
            cleanCompanyField(fixes, apply, company, "city_trim", company.getCity(), company::setCity);
            cleanCompanyField(fixes, apply, company, "website_trim", company.getWebsite(), company::setWebsite);
            String cleanIndustry = canonicalIndustry(company.getIndustry());
            if (cleanIndustry != null && !same(company.getIndustry(), cleanIndustry)) {
                fixes.add(change("industry_canonical", company.getId(), company.getIndustry(), cleanIndustry));
                if (apply) company.setIndustry(cleanIndustry);
            }
        }

        for (DatabaseEmail email : emails) {
            String cleanedEmail = cleanEmail(email.getEmail());
            if (!same(email.getEmail(), cleanedEmail)) {
                List<DatabaseEmail> duplicates = emailsByCleanValue.getOrDefault(cleanedEmail, List.of());
                if (duplicates.stream().anyMatch(other -> !other.getId().equals(email.getId()))) {
                    reviews.add(Map.of(
                            "rule", "email_case_duplicate_needs_manual_merge",
                            "email", cleanedEmail,
                            "ids", duplicates.stream().map(DatabaseEmail::getId).toList()
                    ));
                } else {
                    fixes.add(change("email_lower_trim", email.getId(), email.getEmail(), cleanedEmail));
                    if (apply) email.setEmail(cleanedEmail);
                }
            }

            String domain = emailDomain(cleanedEmail);
            if (!same(email.getDomain(), domain)) {
                fixes.add(change("email_domain_fill", email.getId(), email.getDomain(), domain));
                if (apply) email.setDomain(domain);
            }

            if (PERSONAL_DOMAINS.contains(domain)) {
                if (!"personal".equalsIgnoreCase(safe(email.getEmailType()))) {
                    fixes.add(change("public_email_type_personal", email.getId(), email.getEmailType(), "personal"));
                    if (apply) email.setEmailType("personal");
                }
                if (Boolean.TRUE.equals(email.getIsCorporate())) {
                    fixes.add(change("public_email_not_corporate", email.getId(), true, false));
                    if (apply) email.setIsCorporate(false);
                }
            } else if (!domain.isBlank()) {
                if (!"company".equalsIgnoreCase(safe(email.getEmailType()))) {
                    fixes.add(change("corporate_email_type_company", email.getId(), email.getEmailType(), "company"));
                    if (apply) email.setEmailType("company");
                }
                if (!Boolean.TRUE.equals(email.getIsCorporate())) {
                    fixes.add(change("corporate_email_is_corporate", email.getId(), false, true));
                    if (apply) email.setIsCorporate(true);
                }
            }
        }

        if (apply) {
            fixes.addAll(applyCompanyGroupFixes(companies));
            groupRepository.saveAll(groups);
            companyRepository.saveAll(companies);
            databaseEmailRepository.saveAll(emails);
            fixes.addAll(applyCanonicalGroupMerges());
            groups = groupRepository.findAll();
            companies = companyRepository.findAll();
        }

        duplicateGroupReviews(groups, reviews);
        placeholderGroupReviews(groups, companies, reviews);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", apply ? "apply" : "preview");
        result.put("fixCount", fixes.size());
        result.put("reviewCount", reviews.size());
        result.put("fixes", fixes.stream().limit(200).toList());
        result.put("reviews", reviews.stream().limit(200).toList());
        return result;
    }

    private List<Map<String, Object>> applyCompanyGroupFixes(List<Company> companies) {
        List<Map<String, Object>> fixes = new ArrayList<>();
        for (Company company : companies) {
            CompanyGroupRule rule = companyGroupRule(company.getName());
            if (rule == null) continue;

            Group target = findOrCreateGroup(rule.groupName());
            Long currentGroupId = company.getGroup() != null ? company.getGroup().getId() : null;
            if (!target.getId().equals(currentGroupId)) {
                fixes.add(change("company_group_canonical", company.getId(),
                        company.getGroup() != null ? company.getGroup().getName() : null,
                        target.getName()));
                company.setGroup(target);
            }
            if (rule.brandName() != null && !same(company.getBrandName(), rule.brandName())) {
                fixes.add(change("company_brand_canonical", company.getId(), company.getBrandName(), rule.brandName()));
                company.setBrandName(rule.brandName());
            }
        }
        return fixes;
    }

    private Map<String, Object> auditCompany(Company company) {
        String currentGroup = company.getGroup() != null ? company.getGroup().getName() : "";
        CompanyGroupRule rule = companyGroupRule(company.getName());
        List<String> issues = new ArrayList<>();
        String suggestedIndustry = obviousIndustry(company.getName(), company.getIndustry());

        if (rule != null && !same(currentGroup, rule.groupName())) {
            issues.add("group_mismatch");
        }
        if (rule != null && rule.brandName() != null && !same(company.getBrandName(), rule.brandName())) {
            issues.add("brand_mismatch");
        }
        if (isNoisyCompanyName(company.getName())) {
            issues.add("company_name_needs_review");
        }
        if (isSuspiciousGroupName(currentGroup)) {
            issues.add("group_needs_review");
        }
        if (suggestedIndustry != null) {
            issues.add("industry_mismatch");
        }
        if (isSuspiciousWebsite(company.getWebsite())) {
            issues.add("website_needs_review");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", company.getId());
        row.put("name", company.getName());
        row.put("brandName", company.getBrandName());
        row.put("industry", company.getIndustry());
        row.put("city", company.getCity());
        row.put("website", company.getWebsite());
        row.put("currentGroup", currentGroup);
        row.put("suggestedGroup", rule != null ? rule.groupName() : null);
        row.put("suggestedBrand", rule != null ? rule.brandName() : null);
        row.put("suggestedIndustry", suggestedIndustry);
        row.put("status", issues.isEmpty() ? "OK" : rule != null ? "FIXABLE" : "REVIEW");
        row.put("issues", issues);
        return row;
    }

    private CompanyGroupRule companyGroupRule(String companyName) {
        String name = cleanText(companyName).toLowerCase(Locale.ROOT);
        if (name.contains("panasonic gobel energy")) {
            return new CompanyGroupRule("Panasonic Group", "Panasonic Gobel Energy");
        }
        if (name.startsWith("astra ") || name.contains("(astra honda motor)")) {
            return new CompanyGroupRule("Astra Group", null);
        }
        if (name.contains("adhi commuter properti")) {
            return new CompanyGroupRule("Adhi Karya Group", "Adhi Commuter Properti");
        }
        if (name.contains("aerofood indonesia")) {
            return new CompanyGroupRule("Garuda Indonesia Group", "Aerofood ACS");
        }
        if (name.contains("akr corporindo") || name.contains("arjuna utama kimia")) {
            return new CompanyGroupRule("AKR Group", null);
        }
        if (name.contains("bahana pembinaan usaha indonesia")) {
            return new CompanyGroupRule("Indonesia Financial Group (IFG)", "BPUI");
        }
        if (name.contains("artajasa pembayaran elektronis")) {
            return new CompanyGroupRule("Arta Integrasi Teknologi (ARINT)", "Artajasa");
        }
        if (name.contains("bank commonwealth") || name.contains("ocbc nisp") || name.contains("ocbc indonesia")) {
            return new CompanyGroupRule("OCBC Group", name.contains("commonwealth") ? "Bank Commonwealth" : "OCBC Indonesia");
        }
        if (name.contains("china construction bank")) {
            return new CompanyGroupRule("China Construction Bank Group", "CCB Indonesia");
        }
        if (name.contains("bni multifinance")) {
            return new CompanyGroupRule("BNI Group", "BNI Finance");
        }
        if (name.contains("wahana ottomitra multiartha")) {
            return new CompanyGroupRule("Maybank Group", "WOM Finance");
        }
        if (name.contains("bank mayapada")) {
            return new CompanyGroupRule("Mayapada Group", "Bank Mayapada");
        }
        if (name.contains("bank bjb syariah") || name.contains("bank jabar banten syariah")) {
            return new CompanyGroupRule("Government - Local", null);
        }
        if (name.contains("agriaku") || name.contains("habibi garden")) {
            return new CompanyGroupRule("SMB", null);
        }
        if (name.contains("cita mineral investindo")) {
            return new CompanyGroupRule("Harita Group", "Cita Mineral Investindo (CITA)");
        }
        if (name.contains("core mineral indonesia")) {
            return new CompanyGroupRule("Archean Group", "Core Mineral Indonesia");
        }
        if (name.contains("amman mineral nusa tenggara")) {
            return new CompanyGroupRule("AMMAN Group", "AMMAN");
        }
        if (name.contains("aneka tambang") || name.contains(" antam ") || name.startsWith("antam ")) {
            return new CompanyGroupRule("MIND ID", "ANTAM");
        }
        if (name.contains("asia pulp") || name.equals("app group pt")) {
            return new CompanyGroupRule("Sinar Mas Group", "APP Group");
        }
        if (name.contains("astra graphia")) {
            return new CompanyGroupRule("Astra Group", "Astragraphia");
        }
        if (name.contains("darma henwa")) {
            return new CompanyGroupRule("Bakrie Group", "Darma Henwa (DEWA)");
        }
        if (name.contains("nusa halmahera minerals") || name.contains("newcrest mining")) {
            return new CompanyGroupRule("Indotan Group", "Nusa Halmahera Minerals");
        }
        if (name.contains("nusa halmahera minerlas")) {
            return new CompanyGroupRule("Indotan Group", "Nusa Halmahera Minerals");
        }
        if (name.contains("multikarya asia pasifik raya")) {
            return new CompanyGroupRule("Newmont Corporation", "Newmont");
        }
        if (name.contains("ithaca resources")) {
            return new CompanyGroupRule("Ithaca Group", "Ithaca Resources");
        }
        if (name.contains("asuransi jiwa reliance indonesia")) {
            return new CompanyGroupRule("Reliance Group", "Reliance Life");
        }
        if (name.contains("perkebunan nusantara")) {
            return new CompanyGroupRule("Holding Perkebunan Nusantara (PTPN)", null);
        }
        if (name.equals("adr group pt") || name.contains("selamat sempurna")) {
            return new CompanyGroupRule("ADR Group", "Sakura Filter");
        }
        if (name.contains("arpan bali utama") || name.contains("hatten bali")) {
            return new CompanyGroupRule("Hatten Bali Group", "Hatten Wines");
        }
        if (name.contains("indonesia infrastructure finance")) {
            return new CompanyGroupRule("Government - Central", "IIF");
        }
        if (name.contains("danantara asset management")) {
            return new CompanyGroupRule("Danantara", "Danantara Asset Management");
        }
        if (name.contains("artha daya coalindo")) {
            return new CompanyGroupRule("SMB", "Artha Daya Coalindo");
        }
        if (name.contains("borneo indobara")) {
            return new CompanyGroupRule("Sinar Mas Group", "Borneo Indobara");
        }
        if (name.contains("mega central finance")) {
            return new CompanyGroupRule("CT Corp", "Mega Central Finance");
        }
        if (name.contains("bank jago")) {
            return new CompanyGroupRule("SMB", "Bank Jago");
        }
        if (name.contains("bank ina")) {
            return new CompanyGroupRule("Salim Group", "Bank Ina");
        }
        if (name.contains("asuransi central asia")) {
            return new CompanyGroupRule("Salim Group", "ACA");
        }
        if (name.contains("mizuho leasing indonesia")) {
            return new CompanyGroupRule("Mizuho Leasing Group", "Mizuho Leasing Indonesia");
        }
        if (name.contains("bank tabungan negara") || name.contains("bank btn")) {
            return new CompanyGroupRule("Danantara", "BTN");
        }
        if (name.equals("bank indonesia")) {
            return new CompanyGroupRule("Government - Central", "Bank Indonesia");
        }
        if (name.contains("dana pensiun bank indonesia")) {
            return new CompanyGroupRule("Government - Central", "Dana Pensiun Bank Indonesia");
        }
        if (name.contains("otoritas jasa keuangan")) {
            return new CompanyGroupRule("Government - Central", "OJK");
        }
        if (name.contains("bp tapera")) {
            return new CompanyGroupRule("Government - Central", "BP Tapera");
        }
        if (name.contains("lembaga pembiayaan ekspor indonesia")) {
            return new CompanyGroupRule("Government - Central", "Indonesia Eximbank / LPEI");
        }
        if (name.contains("bursa efek indonesia")) {
            return new CompanyGroupRule("Government - Central", "IDX");
        }
        if (name.contains("kliring penjaminan efek indonesia")) {
            return new CompanyGroupRule("Government - Central", "KPEI");
        }
        if (name.contains("bank bpd bali") || name.contains("bank dki") || name.contains("bank jombang")
                || name.contains("bank pembangunan daerah banten") || name.contains("bank pembangunan daerah jawa barat")
                || name.contains("bumi siak pusako")) {
            return new CompanyGroupRule("Government - Local", null);
        }
        if (name.contains("mandiri tunas finance")) {
            return new CompanyGroupRule("Mandiri Group", "Mandiri Tunas Finance");
        }
        if (name.contains("bank mandiri taspen")) {
            return new CompanyGroupRule("Mandiri Group", "Bank Mandiri Taspen");
        }
        if (name.equals("taspen (persero) pt") || name.startsWith("taspen ") || name.contains("asuransi jiwa taspen")) {
            return new CompanyGroupRule("Danantara", "Taspen");
        }
        if (name.equals("djarum pt") || name.contains(" pt djarum")) {
            return new CompanyGroupRule("Djarum Group", "Djarum");
        }
        if (name.contains("eka hospital")) {
            return new CompanyGroupRule("Sinar Mas Group", "Eka Hospital");
        }
        if (name.contains("indofood sukses makmur")) {
            return new CompanyGroupRule("Salim Group", "Indofood");
        }
        if (name.contains("indomarco prismatama") || name.contains("indomaret")) {
            return new CompanyGroupRule("Indomaret Group", "Indomaret");
        }
        if (name.contains("bank aladin")) {
            return new CompanyGroupRule("Aladin Group", "Bank Aladin Syariah");
        }
        if (name.contains("kb bukopin") || name.contains("kb indonesia")) {
            return new CompanyGroupRule("KB Financial Group", null);
        }
        if (name.contains("etiqa")) {
            return new CompanyGroupRule("Maybank Group", "Etiqa");
        }
        if (name.contains("indosat ooredoo hutchison")) {
            return new CompanyGroupRule("Ooredoo Hutchison Asia", "Indosat / IM3 / Tri");
        }
        if (name.contains("lippo karawaci")) {
            return new CompanyGroupRule("Lippo Group", "Lippo Karawaci");
        }
        if (name.contains("xlsmart")) {
            return new CompanyGroupRule("Sinar Mas Group", "XLSMART, XL, AXIS, Smartfren");
        }
        if (name.contains("amartha financial") || name.contains("amartha mikro")) {
            return new CompanyGroupRule("Amartha Group", "Amartha");
        }
        if (name.contains("cashlez")) {
            return new CompanyGroupRule("SMB", "cashUP");
        }
        if (name.contains("finpoint solusi")) {
            return new CompanyGroupRule("Asaba Group", "Finpoint");
        }
        if (name.contains("indonesia logam pratama")) {
            return new CompanyGroupRule("SMB", "Treasury");
        }
        if (name.contains("integrasi keuangan digital")) {
            return new CompanyGroupRule("SMB", "IKD");
        }
        if (name.contains("nusapay solusi")) {
            return new CompanyGroupRule("SMB", "NUSAPAY");
        }
        if (name.contains("surya anugrah mulya")) {
            return new CompanyGroupRule("SMB", "Surya Anugrah Mulya");
        }
        if (name.contains("erajaya active lifestyle")) {
            return new CompanyGroupRule("Erajaya Group", "Erajaya Active Lifestyle");
        }
        if (name.equals("mnc pt")) {
            return new CompanyGroupRule("MNC Group", "MNC");
        }
        if (name.contains("inaplas") || name.contains("coal metal asia") || name.contains("atlas petrochem")) {
            return new CompanyGroupRule("SMB", null);
        }
        if (name.contains("kim communication") || name.contains("kemenangan indah makmur")) {
            return new CompanyGroupRule("KIM Communication Group", "KIM Communication");
        }
        if (name.equals("kim pt")) {
            return new CompanyGroupRule("KIM Communication Group", "KIM");
        }
        if (name.contains("pembangkitan jaw") || name.contains("pembangkit jawa")) {
            return new CompanyGroupRule("PLN Group", "PLN / PJB");
        }
        if (name.contains("pertamina")) {
            return new CompanyGroupRule("Pertamina Group", "Pertamina");
        }
        if (name.contains("pelat timah nusantara") || name.contains("latinusa")) {
            return new CompanyGroupRule("Nippon Steel", "Latinusa");
        }
        return null;
    }

    private Group findOrCreateGroup(String name) {
        String cleanName = cleanText(name);
        return groupRepository.findByNameIgnoreCase(cleanName).orElseGet(() -> {
            Group group = Group.builder().name(cleanName).build();
            return groupRepository.save(group);
        });
    }

    private void duplicateGroupReviews(List<Group> groups, List<Map<String, Object>> reviews) {
        Map<String, List<Group>> buckets = groups.stream()
                .collect(Collectors.groupingBy(group -> groupKey(group.getName())));

        buckets.values().stream()
                .filter(matches -> matches.size() > 1)
                .forEach(matches -> reviews.add(Map.of(
                        "rule", "possible_duplicate_groups",
                        "names", matches.stream().map(Group::getName).toList(),
                        "ids", matches.stream().map(Group::getId).toList()
                )));
    }

    private List<Map<String, Object>> applyCanonicalGroupMerges() {
        List<Map<String, Object>> fixes = new ArrayList<>();
        Map<String, List<Group>> buckets = groupRepository.findAll().stream()
                .collect(Collectors.groupingBy(group -> groupKey(group.getName())));

        for (List<Group> matches : buckets.values()) {
            if (matches.size() < 2) continue;

            Group target = matches.stream()
                    .filter(group -> safe(group.getName()).toLowerCase(Locale.ROOT).contains("group"))
                    .findFirst()
                    .orElse(matches.get(0));
            List<Long> sourceIds = matches.stream()
                    .map(Group::getId)
                    .filter(id -> !id.equals(target.getId()))
                    .toList();
            if (sourceIds.isEmpty()) continue;

            Map<String, Object> merged = mergeGroups(target.getId(), sourceIds);
            fixes.add(change("canonical_group_merge", target.getId(), sourceIds, merged.get("targetGroupName")));
        }
        return fixes;
    }

    private void placeholderGroupReviews(List<Group> groups, List<Company> companies, List<Map<String, Object>> reviews) {
        Set<Long> placeholderIds = groups.stream()
                .filter(group -> isPlaceholder(group.getName()))
                .map(Group::getId)
                .collect(Collectors.toSet());
        if (placeholderIds.isEmpty()) return;

        List<String> affectedCompanies = companies.stream()
                .filter(company -> company.getGroup() != null && placeholderIds.contains(company.getGroup().getId()))
                .map(Company::getName)
                .limit(50)
                .toList();

        reviews.add(Map.of(
                "rule", "placeholder_group_needs_manual_grouping",
                "groupIds", placeholderIds,
                "sampleCompanies", affectedCompanies
        ));
    }

    private void cleanCompanyField(List<Map<String, Object>> fixes, boolean apply, Company company, String rule,
                                   String oldValue, java.util.function.Consumer<String> setter) {
        String cleaned = cleanText(oldValue);
        if (!same(oldValue, cleaned)) {
            fixes.add(change(rule, company.getId(), oldValue, cleaned));
            if (apply) setter.accept(cleaned);
        }
    }

    private Map<String, Object> change(String rule, Object id, Object from, Object to) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rule", rule);
        row.put("id", id);
        row.put("from", from);
        row.put("to", to);
        return row;
    }

    private String cleanText(String value) {
        return safe(value).trim().replaceAll("\\s+", " ");
    }

    private String cleanEmail(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private String emailDomain(String email) {
        int at = safe(email).indexOf('@');
        return at >= 0 ? email.substring(at + 1).trim().toLowerCase(Locale.ROOT) : "";
    }

    private String groupKey(String value) {
        String key = cleanText(value)
                .toLowerCase(Locale.ROOT)
                .replace(" pt", "")
                .replace(" tbk", "")
                .replace(" group", "")
                .replaceAll("[^a-z0-9]+", "");
        if (key.startsWith("astra")) {
            return "astra";
        }
        if (key.equals("bankcentralasiabca") || key.equals("bca")) {
            return "bca";
        }
        if (key.equals("bankrakyatindonesiabri") || key.equals("bri")) {
            return "bri";
        }
        if (key.equals("independen")) {
            return "independent";
        }
        return CANONICAL_GROUP_KEYS.getOrDefault(key, key);
    }

    private String canonicalIndustry(String value) {
        String industry = cleanText(value);
        if (industry.isBlank()) return null;
        if (industry.startsWith("Financial Service")) {
            return "Financial Services (Banking / Insurance / Multifinance / Fintech)";
        }
        if (industry.equals("Telecommunication")) {
            return "Telecommunications";
        }
        if (industry.matches(".*(Production House|Arts, Entertainment|Media & Advertising|Mining Media).*")) {
            return "Media & Advertising";
        }
        if (industry.startsWith("Education")) {
            return "Education";
        }
        if (industry.equals("Professional Service")) {
            return "Professional Services";
        }
        if (industry.equals("Wholesale/Distributor")) {
            return "Wholesale & Distribution";
        }
        if (industry.matches(".*(Oil & Gas|Mining/Oil/Gas|Energy / Oil|Energy & Oil).*")) {
            return "Oil & Gas";
        }
        if (industry.matches(".*(Coal Mining & Energy|Energy & Mining|Mining & Energy|Mining Holding Company|Mining / Natural Resources|Mining & Oil).*")) {
            return "Mining & Energy";
        }
        if (industry.matches(".*(Metals & Minerals|Nickel|Mining & Metals|Industrial Park & Mineral Processing).*")) {
            return "Mining & Metals";
        }
        if (industry.equals("Mining Association")) {
            return "Mining";
        }
        return null;
    }

    private boolean isPlaceholder(String value) {
        String cleaned = cleanText(value).toLowerCase(Locale.ROOT);
        return cleaned.isBlank() || cleaned.equals("-") || cleaned.equals("--") || cleaned.equals("—") || cleaned.equals("â");
    }

    private boolean isNoisyCompanyName(String value) {
        String cleaned = cleanText(value).toLowerCase(Locale.ROOT);
        return cleaned.equals("pt") || cleaned.equals("others") || cleaned.equals("perlu verifikasi")
                || cleaned.contains("sudah resign") || cleaned.contains("risegn")
                || cleaned.endsWith(" others") || cleaned.contains("pt pt") || cleaned.contains("tbk, pt")
                || cleaned.contains(", pt");
    }

    private boolean isSuspiciousGroupName(String value) {
        String cleaned = cleanText(value).toLowerCase(Locale.ROOT);
        return cleaned.contains("/") || cleaned.contains("ecosystem") || cleaned.contains("consortium");
    }

    private boolean isSuspiciousWebsite(String value) {
        String cleaned = cleanText(value).toLowerCase(Locale.ROOT);
        return cleaned.equals("-") || cleaned.equals("--") || cleaned.equals("—") || cleaned.equals("â€”")
                || cleaned.equals("Ã¢Â€Â”") || cleaned.contains("tidak ditemukan");
    }

    private String obviousIndustry(String companyName, String currentIndustry) {
        String name = cleanText(companyName).toLowerCase(Locale.ROOT);
        if (same(currentIndustry, "Government / Public Institution")) {
            return null;
        }
        if (name.matches(".*(finance|multifinance|bank|asuransi|insurance|sekuritas).*")
                && !cleanText(currentIndustry).startsWith("Financial Services")) {
            return "Financial Services (Banking / Insurance / Multifinance / Fintech)";
        }
        if (name.matches(".*(hotel|resort|villa|marriott|mercure|novotel|ibis).*")
                && !same(currentIndustry, "Hospitality")) {
            return "Hospitality";
        }
        if (name.matches(".*(hospital|siloam|eka hospital).*") && !same(currentIndustry, "Healthcare")) {
            return "Healthcare";
        }
        if (!name.matches(".*(hotel|resort|villa|marriott|mercure|novotel|ibis).*")
                && name.matches(".*(property|properti|karawaci|real estate).*")
                && !same(currentIndustry, "Property & Real Estate")) {
            return "Property & Real Estate";
        }
        return null;
    }

    private boolean same(Object left, Object right) {
        return String.valueOf(left == null ? "" : left).equals(String.valueOf(right == null ? "" : right));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record CompanyGroupRule(String groupName, String brandName) {}
}
