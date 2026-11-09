package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticipantOrganizationFieldsTest {
    private final DatabaseController controller=new DatabaseController();
    private final DatabaseRepository databases=mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails=mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies=mock(CompanyRepository.class);
    private final CompanyBranchRepository branches=mock(CompanyBranchRepository.class);

    @BeforeEach void setup(){
        var security=mock(SecurityHelper.class);var user=AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);when(security.hasAnyRole(user,Role.ADMIN,Role.MANAGER)).thenReturn(true);
        when(databases.save(any())).thenAnswer(call->{Database value=call.getArgument(0);if(value.getId()==null)value.setId(42L);return value;});
        ReflectionTestUtils.setField(controller,"databaseRepository",databases);ReflectionTestUtils.setField(controller,"databaseEmailRepository",emails);
        ReflectionTestUtils.setField(controller,"companyRepository",companies);ReflectionTestUtils.setField(controller,"companyBranchRepository",branches);
        ReflectionTestUtils.setField(controller,"flaggedIdentityRepository",mock(FlaggedIdentityRepository.class));ReflectionTestUtils.setField(controller,"securityHelper",security);
        ReflectionTestUtils.setField(controller,"suspiciousIdentityService",mock(SuspiciousIdentityService.class));
    }

    @Test void allOrganizationFieldsBelongToParticipantAndSparseEditsNeverTouchSameIdentityCompany() throws Exception {
        Company master=Company.builder().id(11L).name("Original Company").brandName("Original Brand").address("Original address").build();
        CompanyBranch branch=CompanyBranch.builder().id(12L).companyId(11L).name("Original office").address("Original branch address").build();
        Database company=Database.builder().id(8L).databaseDestination(DatabaseDestination.COMPANY).firstName("Student").company(master).branch(branch).mobilePhone("081200001111").isActive(true).build();
        DatabaseEmail companyEmail=DatabaseEmail.builder().id(9L).database(company).email("student@campus.ac.id").emailType("personal").build();company.setEmails(List.of(companyEmail));
        when(emails.findAllByEmailIgnoreCase("student@campus.ac.id")).thenReturn(List.of(companyEmail));when(databases.findAll()).thenReturn(List.of(company));
        String original=new ObjectMapper().writeValueAsString(company);
        Database request=fullParticipant();var created=controller.createDatabase(request,null,null,false,"test");assertEquals(200,created.getStatusCode().value());
        assertEquals(42L,request.getId());assertNull(request.getCompany());assertNull(request.getBranch());
        Map<?,?> json=new ObjectMapper().convertValue(request,Map.class);
        for(var entry:organizationValues().entrySet())assertEquals(entry.getValue(),json.get(entry.getKey()),entry.getKey());
        assertEquals(original,new ObjectMapper().writeValueAsString(company));assertFalse((Boolean)ReflectionTestUtils.invokeMethod(controller,"participantIncomplete",request));
        when(databases.findById(42L)).thenReturn(Optional.of(request));
        assertEquals(200,controller.updateDatabase(42L,Database.builder().organizationAddress("").organizationIndustry("Education Updated").build(),null,null,true,"test").getStatusCode().value());
        assertEquals("",request.getOrganizationAddress());assertEquals("Education Updated",request.getOrganizationIndustry());
        assertEquals("Campus Group",request.getOrganizationGroupName());assertEquals("Campus Office",request.getOrganizationBranchName());
        assertEquals(original,new ObjectMapper().writeValueAsString(company));verify(databases,never()).save(company);verifyNoInteractions(companies,branches);
    }

    @Test void optionalOrganizationTextDoesNotBlockUnnamedImportQualityAndRepeatCreateOnlyFillsBlanks(){
        Database unnamed=fullParticipant();unnamed.setFirstName("");unnamed.setOrganizationAddress("A".repeat(3000));unnamed.setOrganizationWebsite("https://campus.example/"+"a".repeat(3000));
        assertEquals(200,controller.createDatabase(unnamed,null,null,false,"test").getStatusCode().value());
        assertTrue((Boolean)ReflectionTestUtils.invokeMethod(controller,"participantIncomplete",unnamed));
        unnamed.setFirstName("Student");unnamed.setOrganizationGroupName("Saved Group");unnamed.setOrganizationIndustry("");
        DatabaseEmail stored=unnamed.getEmails().get(0);stored.setDatabase(unnamed);when(emails.findAllByEmailIgnoreCase("student@campus.ac.id")).thenReturn(List.of(stored));
        Database incoming=fullParticipant();assertSame(unnamed,controller.createDatabase(incoming,null,null,false,"test").getBody());
        assertEquals("Saved Group",unnamed.getOrganizationGroupName());assertEquals("Education",unnamed.getOrganizationIndustry());assertEquals(3000,unnamed.getOrganizationAddress().length());
        verifyNoInteractions(companies,branches);
    }

    @Test void boundedOrganizationFieldsRejectBeforeAnySavedParticipantOrCompanyIsChanged(){
        Map<String,Integer> bounds=Map.of("organizationGroupName",255,"organizationBrandName",255,"organizationOfficePhone",50,
                "organizationIndustry",100,"organizationSizeRevenue",100,"organizationSizeEmployee",100,"organizationBranchName",255,"organizationPostalCode",20);
        Database existing=fullParticipant();existing.setId(42L);when(databases.findById(42L)).thenReturn(Optional.of(existing));
        for(var entry:bounds.entrySet()){
            Database create=fullParticipant();ReflectionTestUtils.setField(create,entry.getKey(),"x".repeat(entry.getValue()+1));
            assertEquals(400,controller.createDatabase(create,null,null,false,"test").getStatusCode().value(),entry.getKey());
            Database update=Database.builder().firstName("Must remain unchanged").build();ReflectionTestUtils.setField(update,entry.getKey(),"x".repeat(entry.getValue()+1));
            assertEquals(400,controller.updateDatabase(42L,update,null,null,true,"test").getStatusCode().value(),entry.getKey());
            assertEquals("Student",existing.getFirstName());assertEquals("Campus Group",existing.getOrganizationGroupName());
        }
        verify(databases,never()).save(any());verifyNoInteractions(companies,branches);
    }

    @Test void participantSortingAndIndustryAnalyticsUseOwnOrganizationText(){
        Database participant=fullParticipant();
        Map<String,String> sorts=Map.of("companyName","example campus","groupName","campus group","brandName","campus brand","industry","education");
        for(var entry:sorts.entrySet())assertEquals(entry.getValue(),ReflectionTestUtils.invokeMethod(controller,"getSortString",participant,entry.getKey()));
        Map<?,?> analytics=ReflectionTestUtils.invokeMethod(controller,"databaseAnalytics",List.of(participant),"participant");
        assertEquals(List.of(Map.of("name","Education","count",1L)),analytics.get("industries"));
        Database company=Database.builder().databaseDestination(DatabaseDestination.COMPANY).institutionName("Ignored campus")
                .organizationIndustry("Ignored participant industry").company(Company.builder().name("Original Company").industry("Manufacturing").build()).build();
        assertEquals("original company",ReflectionTestUtils.invokeMethod(controller,"getSortString",company,"companyName"));
        assertEquals("manufacturing",ReflectionTestUtils.invokeMethod(controller,"getSortString",company,"industry"));
    }

    private static Database fullParticipant(){return Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Student").institutionName("Example Campus")
            .mobilePhone("081200001111").emails(List.of(DatabaseEmail.builder().email("student@campus.ac.id").emailType("personal").build()))
            .organizationGroupName("Campus Group").organizationBrandName("Campus Brand").organizationAddress("Campus address")
            .organizationOfficePhone("0215550001 ext 12").organizationIndustry("Education").organizationSizeRevenue("100M")
            .organizationSizeEmployee("100-500").organizationBranchName("Campus Office").organizationPostalCode("12345").organizationWebsite("https://campus.example").build();}
    private static Map<String,String> organizationValues(){return Map.of("organizationGroupName","Campus Group","organizationBrandName","Campus Brand","organizationAddress","Campus address",
            "organizationOfficePhone","0215550001 ext 12","organizationIndustry","Education","organizationSizeRevenue","100M","organizationSizeEmployee","100-500",
            "organizationBranchName","Campus Office","organizationPostalCode","12345","organizationWebsite","https://campus.example");}
}
