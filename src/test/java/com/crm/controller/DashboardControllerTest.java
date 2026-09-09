package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardControllerTest {
    @Test void industrySummaryRequiresAuthenticationAndDoesNotReadOnFailure() {
        DashboardController controller = new DashboardController();
        CompanyRepository companies = mock(CompanyRepository.class);
        ReflectionTestUtils.setField(controller,"companyRepository",companies);
        ReflectionTestUtils.setField(controller,"securityHelper",mock(SecurityHelper.class));
        assertEquals(401, controller.getIndustrySummary(null).getStatusCode().value());
        verifyNoInteractions(companies);
    }

    @Test void industrySummaryReturnsAllBucketsAndReconcilesTotalsIncludingInactiveContacts() {
        DashboardController controller = new DashboardController();
        CompanyRepository companies = mock(CompanyRepository.class);
        SecurityHelper security = mock(SecurityHelper.class);
        when(security.getAuthenticatedUser("test")).thenReturn(AppUser.builder().id(1L).build());
        ReflectionTestUtils.setField(controller,"companyRepository",companies);
        ReflectionTestUtils.setField(controller,"securityHelper",security);
        var rows = IntStream.rangeClosed(1,9).mapToObj(i -> industry("Industry " + i,1,3,2,1)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        rows.add(industry("Unspecified",0,1,1,0));
        rows.add(industry("No contacts",2,0,0,0));
        when(companies.summarizeIndustries()).thenReturn(rows);
        Map<?,?> body = (Map<?,?>)controller.getIndustrySummary("test").getBody();
        assertEquals(11,((List<?>)body.get("items")).size());
        assertEquals(Map.of("industries",11,"companies",11L,"databases",28L,"activeDatabases",19L,"inactiveDatabases",9L),body.get("totals"));
        when(companies.summarizeIndustries()).thenReturn(List.of());
        body = (Map<?,?>)controller.getIndustrySummary("test").getBody();
        assertEquals(List.of(),body.get("items"));
        assertEquals(Map.of("industries",0,"companies",0L,"databases",0L,"activeDatabases",0L,"inactiveDatabases",0L),body.get("totals"));
        verify(companies,never()).findAll();
    }

    private CompanyRepository.IndustrySummary industry(String name,long companies,long total,long active,long inactive) {
        return new CompanyRepository.IndustrySummary() {
            public String getIndustry() { return name; }
            public long getCompanyCount() { return companies; }
            public long getDatabaseCount() { return total; }
            public long getActiveDatabaseCount() { return active; }
            public long getInactiveDatabaseCount() { return inactive; }
        };
    }

    @Test void allIndustriesAreReturnedNotOnlySixAndCountCompanies() {
        DashboardController controller=new DashboardController();
        CompanyRepository companies=mock(CompanyRepository.class);
        SecurityHelper security=mock(SecurityHelper.class);
        AppUser admin=AppUser.builder().id(1L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(admin);
        when(security.hasRole(admin,Role.ADMIN)).thenReturn(true);
        List<Company> records=new ArrayList<>(IntStream.rangeClosed(1,9).mapToObj(i->Company.builder().industry("Industry "+i).build()).toList());
        records.add(Company.builder().industry("Industry 1").build());
        records.add(Company.builder().industry(" ").build());
        when(companies.findAll()).thenReturn(records);
        ReflectionTestUtils.setField(controller,"companyRepository",companies);
        ReflectionTestUtils.setField(controller,"securityHelper",security);
        ReflectionTestUtils.setField(controller,"groupRepository",mock(GroupRepository.class));
        ReflectionTestUtils.setField(controller,"databaseRepository",mock(DatabaseRepository.class));
        ReflectionTestUtils.setField(controller,"eventRepository",mock(EventRepository.class));
        ReflectionTestUtils.setField(controller,"eventParticipantRepository",mock(EventParticipantRepository.class));
        ReflectionTestUtils.setField(controller,"flaggedIdentityRepository",mock(FlaggedIdentityRepository.class));
        Map<?,?> response=(Map<?,?>)controller.getDashboardSummary("test").getBody();
        List<?> industries=(List<?>)response.get("industryDistribution");
        assertEquals(10,industries.size());
        assertEquals(Map.of("name","Industry 1","value",2L),industries.get(0));
        assertEquals(11L,industries.stream().mapToLong(item->(Long)((Map<?,?>)item).get("value")).sum());
    }
}
