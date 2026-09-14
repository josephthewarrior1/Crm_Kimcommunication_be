package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class CompanyBranchControllerTest {
    private final CompanyBranchRepository branches = mock(CompanyBranchRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private MockMvc mvc;
    private final CompanyBranch branch = CompanyBranch.builder().id(10L).companyId(1L).name("Pademangan").build();

    @BeforeEach void setup() {
        SecurityHelper security = mock(SecurityHelper.class);
        for (Role role : Role.values()) {
            AppUser user = AppUser.builder().id(1L).roles(Set.of(role)).build();
            when(security.getAuthenticatedUser(role.name())).thenReturn(user);
            when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(role != Role.USER);
        }
        when(companies.existsById(1L)).thenReturn(true);
        when(branches.findById(10L)).thenReturn(Optional.of(branch));
        when(branches.findByCompanyIdOrderByNameAsc(1L)).thenReturn(List.of(branch));
        when(branches.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        mvc = MockMvcBuilders.standaloneSetup(new CompanyBranchController(branches, companies, databases, security))
                .setControllerAdvice(new com.crm.config.GlobalExceptionHandler()).build();
    }

    @Test void readRequiresAuthenticationAndWritesRequireAdminOrPic() throws Exception {
        mvc.perform(get("/api/companies/1/branches")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/companies/1/branches").header("Authorization", "USER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].companyId").value(1))
                .andExpect(jsonPath("$[0].company").doesNotExist());
        mvc.perform(post("/api/companies/1/branches").contentType(APPLICATION_JSON).content("{\"name\":\"Bandung\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/companies/1/branches").header("Authorization", "USER").contentType(APPLICATION_JSON).content("{\"name\":\"Bandung\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/companies/1/branches/10").header("Authorization", "USER").contentType(APPLICATION_JSON).content("{\"name\":\"Bandung\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/companies/1/branches/10").header("Authorization", "USER")).andExpect(status().isForbidden());
        verify(branches, never()).saveAndFlush(any());
        verify(branches, never()).deleteById(any());
    }

    @Test void trimsInputRejectsBlankLongAndCaseInsensitiveDuplicateName() throws Exception {
        mvc.perform(post("/api/companies/1/branches").header("Authorization", "MANAGER").contentType(APPLICATION_JSON)
                .content("{\"name\":\" Bandung \",\"city\":\" Bandung \",\"companyId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bandung"))
                .andExpect(jsonPath("$.companyId").value(1)).andExpect(jsonPath("$.city").value("Bandung"));
        mvc.perform(post("/api/companies/1/branches").header("Authorization", "ADMIN").contentType(APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/companies/1/branches").header("Authorization", "ADMIN").contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        when(branches.findByCompanyIdAndNameIgnoreCase(1L, "pademangan")).thenReturn(Optional.of(branch));
        mvc.perform(post("/api/companies/1/branches").header("Authorization", "ADMIN").contentType(APPLICATION_JSON).content("{\"name\":\"pademangan\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/companies/999/branches").header("Authorization", "ADMIN").contentType(APPLICATION_JSON).content("{\"name\":\"Bandung\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void updateAndDeleteAreScopedToCompanyAndDeletionKeepsAssignedContacts() throws Exception {
        mvc.perform(put("/api/companies/2/branches/10").header("Authorization", "ADMIN").contentType(APPLICATION_JSON).content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/companies/2/branches/10").header("Authorization", "ADMIN")).andExpect(status().isNotFound());
        when(branches.findByCompanyIdAndNameIgnoreCase(1L, "Pademangan")).thenReturn(Optional.of(branch));
        mvc.perform(put("/api/companies/1/branches/10").header("Authorization", "MANAGER").contentType(APPLICATION_JSON)
                .content("{\"name\":\"Pademangan\",\"address\":\" Jl. Baru \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").value("Jl. Baru"));
        when(databases.existsByBranchId(10L)).thenReturn(true);
        mvc.perform(delete("/api/companies/1/branches/10").header("Authorization", "ADMIN")).andExpect(status().isConflict());
        verify(branches, never()).deleteById(any());
        verify(databases, never()).save(any());
        when(databases.existsByBranchId(10L)).thenReturn(false);
        mvc.perform(delete("/api/companies/1/branches/10").header("Authorization", "MANAGER")).andExpect(status().isNoContent());
        verify(branches).deleteById(10L);
    }
}
