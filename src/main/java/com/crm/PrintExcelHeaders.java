package com.crm;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileInputStream;

public class PrintExcelHeaders {
    public static void main(String[] args) {
        String filePath = "Database Template (1).xlsx";
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                System.out.println("=== EXCEL HEADERS ===");
                for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                    Cell cell = headerRow.getCell(i);
                    System.out.println("Col " + i + ": " + (cell != null ? cell.toString() : "null"));
                }
                System.out.println("=====================");
            } else {
                System.out.println("Header row is null!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
