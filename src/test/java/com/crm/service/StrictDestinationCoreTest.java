package com.crm.service;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrictDestinationCoreTest {
    @Test void participantCleanerNeverTouchesCompanyMastersOrSameAddressInCompanyAndTokensBindScope() {
        var cleaner=new DataCleanerService(); var groups=mock(GroupRepository.class); var companies=mock(CompanyRepository.class);
        var emails=mock(DatabaseEmailRepository.class);
        ReflectionTestUtils.setField(cleaner,"groupRepository",groups);ReflectionTestUtils.setField(cleaner,"companyRepository",companies);
        ReflectionTestUtils.setField(cleaner,"databaseEmailRepository",emails);
        Group masterGroup=Group.builder().id(1L).name(" Original Group ").build();
        Company master=Company.builder().id(1L).name(" Original Company ").group(masterGroup).build();
        Database corporate=Database.builder().id(1L).databaseDestination(DatabaseDestination.COMPANY).company(master).build();
        Database participant=Database.builder().id(2L).databaseDestination(DatabaseDestination.PARTICIPANT).build();
        DatabaseEmail companyAddress=address(11,corporate," SAME@CAMPUS.AC.ID ");
        DatabaseEmail participantAddress=address(12,participant," SAME@CAMPUS.AC.ID ");
        when(emails.findAll()).thenReturn(List.of(companyAddress,participantAddress));
        when(groups.findAll()).thenReturn(List.of(masterGroup));when(companies.findAll()).thenReturn(List.of(master));
        var preview=cleaner.preview("participant");assertEquals("participant",preview.get("destination"));assertEquals(0,preview.get("reviewCount"));
        cleaner.apply("participant",(String)preview.get("previewToken"));
        assertEquals("same@campus.ac.id",participantAddress.getEmail());assertEquals("personal",participantAddress.getEmailType());
        assertEquals(" SAME@CAMPUS.AC.ID ",companyAddress.getEmail());assertEquals(" Original Company ",master.getName());assertEquals(" Original Group ",masterGroup.getName());
        verify(companies,never()).findAll();verify(groups,never()).findAll();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->cleaner.apply("company",(String)preview.get("previewToken"))).getStatusCode().value());
        assertEquals(" Original Company ",master.getName());
        var latest=cleaner.preview("participant");participantAddress.setEmail("changed@example.invalid");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->cleaner.apply("participant",(String)latest.get("previewToken"))).getStatusCode().value());
        verify(companies,never()).saveAll(any());verify(groups,never()).saveAll(any());
    }

    @Test void duplicateChecksAndManualFlagsIgnoreOppositeDestinationButStillFlagSameKind() {
        var service=new SuspiciousIdentityService();var databases=mock(DatabaseRepository.class);var emails=mock(DatabaseEmailRepository.class);var flags=mock(FlaggedIdentityRepository.class);
        ReflectionTestUtils.setField(service,"databaseRepository",databases);ReflectionTestUtils.setField(service,"databaseEmailRepository",emails);ReflectionTestUtils.setField(service,"flaggedIdentityRepository",flags);
        Database participant=Database.builder().id(2L).databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Participant").mobilePhone("081200001111").build();
        Database corporate=Database.builder().id(1L).databaseDestination(DatabaseDestination.COMPANY).firstName("Company Person").mobilePhone("081200001111").isActive(true).build();
        DatabaseEmail participantAddress=address(12,participant,"same@example.invalid");DatabaseEmail companyAddress=address(11,corporate,"same@example.invalid");participant.setEmails(List.of(participantAddress));
        FlaggedIdentity oldCompanyFlag=FlaggedIdentity.builder().id(9L).database(corporate).emailUsed("same@example.invalid").status(FlagStatus.confirmed).build();
        when(databases.findByNormalizedPhone("+6281200001111")).thenReturn(List.of(corporate));when(emails.findAllByEmailIgnoreCase("same@example.invalid")).thenReturn(List.of(companyAddress));when(flags.findAll()).thenReturn(List.of(oldCompanyFlag));
        service.checkAndFlagDatabase(participant);verify(flags,never()).save(any());assertTrue(corporate.getIsActive());assertSame(corporate,oldCompanyFlag.getDatabase());
        corporate.setDatabaseDestination(DatabaseDestination.PARTICIPANT);service.checkAndFlagDatabase(participant);verify(flags,atLeast(2)).save(any());
    }

    @Test void mergeRejectsOppositeDestinationsAndPreservesParticipantMetadataWithinOneDestination() {
        var jdbc=mock(JdbcTemplate.class);var service=new DatabaseMergeService(jdbc,new ObjectMapper());
        Map<String,Object> target=profile(1,"COMPANY"),source=profile(2,"PARTICIPANT");source.put("institution_name","Source Campus");source.put("participant_category","mahasiswa");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Map<String,Object>>>any(), any(Object[].class)))
                .thenAnswer(call->((String)call.getArgument(0)).contains("(SELECT * FROM databases WHERE id IN")?List.of(target,source):List.of());
        var actor=AppUser.builder().id(7L).roles(Set.of(Role.ADMIN)).build();var request=new DatabaseMergeService.MergeRequest(1L,2L,Map.of(),Map.of(),null);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.merge(request,actor,false)).getStatusCode().value());
        target.put("database_destination","PARTICIPANT");target.put("mobile_phone","081200001111, 081300002222");source.put("mobile_phone","+6281300002222");
        var preview=service.merge(request,actor,false);
        Map<?,?> merged=(Map<?,?>)preview.get("contact");assertEquals("Source Campus",merged.get("institution_name"));assertEquals("mahasiswa",merged.get("participant_category"));
        assertEquals("+6281200001111",merged.get("normalized_phone"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    private static DatabaseEmail address(long id,Database database,String value){return DatabaseEmail.builder().id(id).database(database).email(value).emailType("personal").isCorporate(false).build();}
    private static Map<String,Object> profile(long id,String destination){var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("database_destination",destination);value.put("first_name","Same");value.put("last_name","Person");value.put("mobile_phone","081200001111");value.put("is_active",true);return value;}
}
