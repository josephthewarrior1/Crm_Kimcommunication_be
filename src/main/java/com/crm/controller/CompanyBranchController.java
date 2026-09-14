package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.CompanyBranch;
import com.crm.domain.Role;
import com.crm.repository.CompanyBranchRepository;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.service.SecurityHelper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/branches")
public class CompanyBranchController {
    public record BranchInput(@NotBlank @Size(max = 255) String name,
                              String address, @Size(max = 100) String city,
                              @Size(max = 20) String postalCode, @Size(max = 50) String officePhone) {}

    private final CompanyBranchRepository branches;
    private final CompanyRepository companies;
    private final DatabaseRepository databases;
    private final SecurityHelper security;

    public CompanyBranchController(CompanyBranchRepository branches, CompanyRepository companies,
                                   DatabaseRepository databases, SecurityHelper security) {
        this.branches = branches;
        this.companies = companies;
        this.databases = databases;
        this.security = security;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long companyId,
                                 @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (security.getAuthenticatedUser(authHeader) == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!companies.existsById(companyId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(branches.findByCompanyIdOrderByNameAsc(companyId));
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long companyId, @Valid @RequestBody BranchInput input,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<?> denied = authorizeWrite(authHeader);
        if (denied != null) return denied;
        if (!companies.existsById(companyId)) return ResponseEntity.notFound().build();
        return save(CompanyBranch.builder().companyId(companyId).build(), input);
    }

    @PutMapping("/{branchId}")
    public ResponseEntity<?> update(@PathVariable Long companyId, @PathVariable Long branchId,
                                   @Valid @RequestBody BranchInput input,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<?> denied = authorizeWrite(authHeader);
        if (denied != null) return denied;
        CompanyBranch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !companyId.equals(branch.getCompanyId())) return ResponseEntity.notFound().build();
        return save(branch, input);
    }

    @DeleteMapping("/{branchId}")
    public ResponseEntity<?> delete(@PathVariable Long companyId, @PathVariable Long branchId,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<?> denied = authorizeWrite(authHeader);
        if (denied != null) return denied;
        CompanyBranch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !companyId.equals(branch.getCompanyId())) return ResponseEntity.notFound().build();
        if (databases.existsByBranchId(branchId)) {
            return ResponseEntity.status(409).body("Cabang masih digunakan kontak. Pindahkan kontak terlebih dahulu.");
        }
        try {
            branches.deleteById(branchId);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(409).body("Cabang masih digunakan kontak. Pindahkan kontak terlebih dahulu.");
        }
    }

    private ResponseEntity<?> save(CompanyBranch branch, BranchInput input) {
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) return ResponseEntity.badRequest().body("Nama cabang wajib diisi");
        CompanyBranch duplicate = branches.findByCompanyIdAndNameIgnoreCase(branch.getCompanyId(), name).orElse(null);
        if (duplicate != null && !duplicate.getId().equals(branch.getId())) {
            return ResponseEntity.status(409).body("Nama cabang sudah digunakan di perusahaan ini");
        }
        branch.setName(name);
        branch.setAddress(trim(input.address()));
        branch.setCity(trim(input.city()));
        branch.setPostalCode(trim(input.postalCode()));
        branch.setOfficePhone(trim(input.officePhone()));
        try {
            return ResponseEntity.ok(branches.saveAndFlush(branch));
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(409).body("Cabang tidak dapat disimpan. Periksa perusahaan dan nama cabang.");
        }
    }

    private ResponseEntity<?> authorizeWrite(String authHeader) {
        AppUser user = security.getAuthenticatedUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)) return ResponseEntity.status(403).body("Forbidden");
        return null;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body("Periksa nama cabang dan panjang kolom yang diisi");
    }
}
