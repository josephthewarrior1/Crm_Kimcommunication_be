package com.crm.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelImportControllerTest {

    @Test
    void personalEmailColumnOnlyAcceptsPublicPersonalDomains() {
        assertEquals(List.of(), ExcelImportController.getCorporateEmailsInPersonalColumn("person@gmail.com; other@yahoo.co.id"));
        assertEquals(List.of("person@company.co.id"), ExcelImportController.getCorporateEmailsInPersonalColumn("person@company.co.id"));
        assertEquals(List.of("staff@company.com"), ExcelImportController.getCorporateEmailsInPersonalColumn("owner@outlook.com, staff@company.com"));
    }
}
