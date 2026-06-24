const fs = require('fs');
const path = require('path');

const xlDir = path.join(__dirname, '..', 'extracted_excel', 'xl');
const sharedStringsPath = path.join(xlDir, 'sharedStrings.xml');
const sheet1Path = path.join(xlDir, 'worksheets', 'sheet1.xml');

if (!fs.existsSync(sharedStringsPath) || !fs.existsSync(sheet1Path)) {
  console.error("Missing sheet or shared strings XML files.");
  process.exit(1);
}

// 1. Parse shared strings
const sharedStringsContent = fs.readFileSync(sharedStringsPath, 'utf8');
const strings = [];
const stringMatches = sharedStringsContent.matchAll(/<t[^>]*>(.*?)<\/t>/g);
for (const match of stringMatches) {
  strings.push(match[1]);
}

// 2. Parse sheet1 cells
const sheetContent = fs.readFileSync(sheet1Path, 'utf8');
const rows = {};

// Match cells: <c r="C2" t="s"><v>12</v></c>
const cellMatches = sheetContent.matchAll(/<c r="([A-Z]+)(\d+)"([^>]*)>(?:<v>(\d+)<\/v>)?/g);
for (const match of cellMatches) {
  const col = match[1];
  const rowNum = parseInt(match[2], 10);
  const typeAttr = match[3];
  const val = match[4];

  if (!rows[rowNum]) rows[rowNum] = {};

  let value = "";
  if (val !== undefined) {
    if (typeAttr.includes('t="s"')) {
      const stringIdx = parseInt(val, 10);
      value = strings[stringIdx] || "";
    } else {
      value = val;
    }
  }
  rows[rowNum][col] = value;
}

// Print rows
console.log("=== EXCEL DATA EXTRACTED ===");
const sortedRowNums = Object.keys(rows).map(Number).sort((a, b) => a - b);
for (const r of sortedRowNums) {
  console.log(`Row ${r}:`, JSON.stringify(rows[r]));
}
