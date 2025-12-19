package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.repository.UserRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final UserRepository users;

    public ExportController(UserRepository users) { this.users = users; }

    @GetMapping(value = "/users.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportUsersCsv() {
        List<AppUser> list = users.findAll();
        String header = "id,name,username,email,roles,active,approved,dob\n";
        String rows = list.stream().map(u -> String.format("%d,%s,%s,\"%s\",%s,%s,%s",
                u.getId(),
                escape(u.getName()),
                escape(u.getUsername()),
                escape(u.getEmail()),
                u.getRoles().toString(),
                Boolean.toString(u.isActive()),
                Boolean.toString(u.isApproved()),
                u.getDob() != null ? u.getDob() : ""
        )).collect(Collectors.joining("\n"));
        byte[] data = (header + rows + "\n").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(data);
    }

    @GetMapping(value = "/users.xlsx")
    public ResponseEntity<byte[]> exportUsersXlsx() throws Exception {
        List<AppUser> list = users.findAll();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("users");
            int r = 0;
            Row h = sheet.createRow(r++);
            String[] cols = {"id","name","username","email","roles","active","approved","dob"};
            for (int i=0;i<cols.length;i++) h.createCell(i).setCellValue(cols[i]);
            for (AppUser u : list) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(u.getId());
                row.createCell(1).setCellValue(nullToEmpty(u.getName()));
                row.createCell(2).setCellValue(nullToEmpty(u.getUsername()));
                row.createCell(3).setCellValue(nullToEmpty(u.getEmail()));
                row.createCell(4).setCellValue(u.getRoles().toString());
                row.createCell(5).setCellValue(u.isActive());
                row.createCell(6).setCellValue(u.isApproved());
                row.createCell(7).setCellValue(u.getDob() != null ? u.getDob().toString() : "");
            }
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    @GetMapping(value = "/templates/projects.csv", produces = "text/csv")
    public ResponseEntity<byte[]> templateProjectsCsv() {
        String header = "name,client,start_date,end_date,status,priority,budget\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=template-projects.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(header.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/templates/projects.xlsx")
    public ResponseEntity<byte[]> templateProjectsXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("projects-template");
            String[] cols = {"name","client","start_date","end_date","status","priority","budget"};
            Row h = sheet.createRow(0);
            for (int i=0;i<cols.length;i++) h.createCell(i).setCellValue(cols[i]);
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=template-projects.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
