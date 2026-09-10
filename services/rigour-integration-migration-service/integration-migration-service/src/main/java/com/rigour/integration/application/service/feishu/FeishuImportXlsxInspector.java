package com.rigour.integration.application.service.feishu;

import com.rigour.integration.infrastructure.config.FeishuImportProperties;
import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.util.StringUtils;

/** 轻量级 XLSX 结构读取器；预检阶段避免把整本工作簿加载为业务对象。 */
final class FeishuImportXlsxInspector {
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String RELATIONSHIP_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final int CELL_VALUE_LIMIT = 1000;

    Inspection inspect(Path file, FeishuImportProperties properties) {
        return inspect(file, properties, List.of());
    }

    Inspection inspect(Path file, FeishuImportProperties properties, List<ImportTemplate> templates) {
        properties.validate();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry workbook = entry(zip, WORKBOOK);
            if (workbook == null) {
                throw new IllegalArgumentException("飞书导出文件不是有效的xlsx工作簿");
            }
            List<String> sharedStrings = sharedStrings(zip);
            Map<String, String> rels = workbookRelationships(zip);
            List<WorkbookSheet> workbookSheets = workbookSheets(zip, rels);
            if (workbookSheets.isEmpty()) {
                throw new IllegalArgumentException("飞书导出文件没有可读取的工作表");
            }
            if (workbookSheets.size() > properties.getMaxSheets()) {
                throw new IllegalArgumentException("飞书导出文件工作表数量超过限制");
            }
            List<SheetInspection> sheets = new ArrayList<>();
            List<Issue> issues = new ArrayList<>();
            for (WorkbookSheet workbookSheet : workbookSheets) {
                ZipEntry sheetEntry = entry(zip, workbookSheet.path());
                if (sheetEntry == null) {
                    issues.add(new Issue("ERROR", "FEISHU_XLSX_SHEET_MISSING",
                            workbookSheet.name(), null, null, "工作表文件缺失，不能导入"));
                    continue;
                }
                SheetInspection sheet = sheet(zip, sheetEntry, workbookSheet.name(), sharedStrings,
                        properties, templates);
                sheets.add(sheet);
                appendSheetIssues(sheet, issues, properties);
            }
            return new Inspection(List.copyOf(sheets), List.copyOf(issues));
        } catch (ZipException exception) {
            throw new IllegalArgumentException("飞书导出文件不是有效的xlsx压缩包", exception);
        } catch (IOException | XMLStreamException exception) {
            throw new IllegalArgumentException("飞书导出文件读取失败", exception);
        }
    }

    private static void appendSheetIssues(SheetInspection sheet, List<Issue> issues,
                                          FeishuImportProperties properties) {
        FeishuImportTableCatalog.Match match = sheet.catalogMatch();
        if ("UNMAPPED_TABLE".equals(match.mappingStatus())) {
            issues.add(new Issue("WARN", "FEISHU_TABLE_UNMAPPED", sheet.sheetName(), null, null,
                    "未识别到内部业务域，需要先配置字段映射后才能落库"));
        }
        for (String missing : match.missingHeaders()) {
            issues.add(new Issue("ERROR", "FEISHU_REQUIRED_FIELD_MISSING",
                    sheet.sheetName(), sheet.headerRowNumber(), missing,
                    "飞书表缺少必需字段：" + missing));
        }
        if ("NEEDS_FIELD_MAPPING".equals(match.mappingStatus()) && match.missingHeaders().isEmpty()) {
            issues.add(new Issue("WARN", "FEISHU_FIELD_MAPPING_REQUIRED",
                    sheet.sheetName(), null, null,
                    "工作表已识别，但还缺少可正式投影的字段映射或领域写入规则"));
        }
        if (!match.attachmentFields().isEmpty() && sheet.attachmentReferenceCount() > 0) {
            issues.add(new Issue("WARN", "FEISHU_ATTACHMENT_SOURCE_REQUIRED",
                    sheet.sheetName(), null, String.join(",", match.attachmentFields()),
                    "检测到附件引用；可先导入业务主体，附件后续通过飞书API或附件包补偿回填"));
        }
        if (sheet.rowCount() > properties.getMaxRowsPerSheet()) {
            issues.add(new Issue("ERROR", "FEISHU_SHEET_ROW_LIMIT_EXCEEDED",
                    sheet.sheetName(), null, null, "工作表数据行数超过单表限制"));
        }
    }

    private static List<String> sharedStrings(ZipFile zip) throws IOException, XMLStreamException {
        ZipEntry entry = entry(zip, SHARED_STRINGS);
        if (entry == null) return List.of();
        XMLInputFactory factory = xmlFactory();
        List<String> values = new ArrayList<>();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            StringBuilder item = null;
            StringBuilder text = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("si".equals(name)) {
                        item = new StringBuilder();
                    } else if ("t".equals(name) && item != null) {
                        text = new StringBuilder();
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && text != null) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("t".equals(name) && item != null && text != null) {
                        item.append(text);
                        text = null;
                    } else if ("si".equals(name) && item != null) {
                        values.add(item.toString());
                        item = null;
                    }
                }
            }
            reader.close();
        }
        return List.copyOf(values);
    }

    private static Map<String, String> workbookRelationships(ZipFile zip) throws IOException, XMLStreamException {
        ZipEntry entry = entry(zip, WORKBOOK_RELS);
        if (entry == null) return Map.of();
        XMLInputFactory factory = xmlFactory();
        Map<String, String> values = new HashMap<>();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())) {
                    String id = reader.getAttributeValue(null, "Id");
                    String target = reader.getAttributeValue(null, "Target");
                    if (StringUtils.hasText(id) && StringUtils.hasText(target)) {
                        values.put(id, normalizeWorkbookTarget(target));
                    }
                }
            }
            reader.close();
        }
        return Map.copyOf(values);
    }

    private static List<WorkbookSheet> workbookSheets(ZipFile zip, Map<String, String> rels)
            throws IOException, XMLStreamException {
        XMLInputFactory factory = xmlFactory();
        List<WorkbookSheet> sheets = new ArrayList<>();
        try (InputStream input = zip.getInputStream(entry(zip, WORKBOOK))) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            int index = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.getLocalName())) {
                    index++;
                    String name = reader.getAttributeValue(null, "name");
                    String relationId = reader.getAttributeValue(RELATIONSHIP_NS, "id");
                    String path = rels.get(relationId);
                    if (!StringUtils.hasText(path)) {
                        path = "xl/worksheets/sheet" + index + ".xml";
                    }
                    sheets.add(new WorkbookSheet(index, clean(name), path));
                }
            }
            reader.close();
        }
        sheets.sort(Comparator.comparingInt(WorkbookSheet::index));
        return List.copyOf(sheets);
    }

    private static SheetInspection sheet(ZipFile zip, ZipEntry entry, String sheetName,
                                         List<String> sharedStrings,
                                         FeishuImportProperties properties,
                                         List<ImportTemplate> templates)
            throws IOException, XMLStreamException {
        XMLInputFactory factory = xmlFactory();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            Map<Integer, String> row = new LinkedHashMap<>();
            Map<Integer, String> headersByColumn = new LinkedHashMap<>();
            List<Map<String, String>> sampleRows = new ArrayList<>();
            List<RowInspection> rows = new ArrayList<>();
            int currentRowNumber = 0;
            int currentColumn = 0;
            String currentCellType = null;
            StringBuilder text = null;
            boolean capturingText = false;
            int headerRowNumber = 0;
            long rowCount = 0L;
            long attachmentReferenceCount = 0L;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("row".equals(name)) {
                        row = new LinkedHashMap<>();
                        currentRowNumber = rowNumber(reader.getAttributeValue(null, "r"), currentRowNumber + 1);
                        currentColumn = 0;
                    } else if ("c".equals(name)) {
                        currentCellType = reader.getAttributeValue(null, "t");
                        currentColumn = columnIndex(reader.getAttributeValue(null, "r"), currentColumn + 1);
                    } else if ("v".equals(name) || ("t".equals(name) && "inlineStr".equals(currentCellType))) {
                        capturingText = true;
                        text = new StringBuilder();
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && capturingText && text != null) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("v".equals(name) || ("t".equals(name) && capturingText)) {
                        String value = cellValue(currentCellType, text == null ? "" : text.toString(), sharedStrings);
                        if (StringUtils.hasText(value)) row.put(currentColumn, truncate(value.strip(), CELL_VALUE_LIMIT));
                        capturingText = false;
                        text = null;
                    } else if ("c".equals(name)) {
                        currentCellType = null;
                    } else if ("row".equals(name)) {
                        if (headerRowNumber == 0 && nonBlankCount(row) >= 2) {
                            headerRowNumber = currentRowNumber;
                            headersByColumn = headers(row, properties.getMaxColumns());
                        } else if (headerRowNumber > 0 && hasValue(row)) {
                            rowCount++;
                            attachmentReferenceCount += attachmentReferences(row, headersByColumn);
                            rows.add(new RowInspection(currentRowNumber, sampleRow(row, headersByColumn),
                                    attachmentReferenceValues(row, headersByColumn)));
                            if (sampleRows.size() < properties.getSampleRows()) {
                                sampleRows.add(sampleRow(row, headersByColumn));
                            }
                        }
                    }
                }
            }
            reader.close();
            List<String> headers = new ArrayList<>(headersByColumn.values());
            FeishuImportTableCatalog.Match match = FeishuImportTableCatalog.match(sheetName, headers, templates);
            return new SheetInspection(clean(sheetName), headerRowNumber, rowCount, headersByColumn.size(),
                    attachmentReferenceCount, List.copyOf(headers), match, List.copyOf(sampleRows),
                    List.copyOf(rows));
        }
    }

    private static Map<Integer, String> headers(Map<Integer, String> row, int maxColumns) {
        Map<Integer, String> headers = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<Integer, String> entry : row.entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) continue;
            count++;
            if (count > maxColumns) throw new IllegalArgumentException("飞书导出文件列数超过限制");
            headers.put(entry.getKey(), entry.getValue().strip());
        }
        return headers;
    }

    private static Map<String, String> sampleRow(Map<Integer, String> row, Map<Integer, String> headersByColumn) {
        Map<String, String> sample = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> header : headersByColumn.entrySet()) {
            String value = row.get(header.getKey());
            if (StringUtils.hasText(value)) sample.put(header.getValue(), value);
        }
        return Map.copyOf(sample);
    }

    private static long attachmentReferences(Map<Integer, String> row, Map<Integer, String> headersByColumn) {
        long count = 0L;
        for (Map.Entry<Integer, String> header : headersByColumn.entrySet()) {
            if (!FeishuImportTableCatalog.attachmentFields(List.of(header.getValue())).isEmpty()) {
                count += attachmentTokenCount(row.get(header.getKey()));
            }
        }
        return count;
    }

    private static Map<String, List<String>> attachmentReferenceValues(
            Map<Integer, String> row, Map<Integer, String> headersByColumn) {
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> header : headersByColumn.entrySet()) {
            if (FeishuImportTableCatalog.attachmentFields(List.of(header.getValue())).isEmpty()) {
                continue;
            }
            List<String> values = splitAttachmentTokens(row.get(header.getKey()));
            if (!values.isEmpty()) refs.put(header.getValue(), values);
        }
        return Map.copyOf(refs);
    }

    private static long attachmentTokenCount(String value) {
        return splitAttachmentTokens(value).size();
    }

    private static List<String> splitAttachmentTokens(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.split("[\\n\\r,，;；]+")) {
            if (StringUtils.hasText(token)) result.add(truncate(token.strip(), CELL_VALUE_LIMIT));
        }
        return List.copyOf(result);
    }

    private static boolean hasValue(Map<Integer, String> row) {
        return nonBlankCount(row) > 0;
    }

    private static int nonBlankCount(Map<Integer, String> row) {
        int count = 0;
        for (String value : row.values()) {
            if (StringUtils.hasText(value)) count++;
        }
        return count;
    }

    private static String cellValue(String cellType, String raw, List<String> sharedStrings) {
        if (!StringUtils.hasText(raw)) return null;
        if ("s".equals(cellType)) {
            try {
                int index = Integer.parseInt(raw.strip());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : raw;
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if ("b".equals(cellType)) return "1".equals(raw.strip()) ? "TRUE" : "FALSE";
        return raw;
    }

    private static int rowNumber(String value, int fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int columnIndex(String cellReference, int fallback) {
        if (!StringUtils.hasText(cellReference)) return fallback;
        int column = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char character = Character.toUpperCase(cellReference.charAt(i));
            if (character < 'A' || character > 'Z') break;
            column = column * 26 + (character - 'A' + 1);
        }
        return column == 0 ? fallback : column;
    }

    private static String normalizeWorkbookTarget(String target) {
        String value = target.replace('\\', '/').strip();
        if (value.startsWith("/")) value = value.substring(1);
        if (!value.startsWith("xl/")) value = "xl/" + value;
        while (value.contains("//")) value = value.replace("//", "/");
        if (value.contains("../")) throw new IllegalArgumentException("飞书导出文件包含不安全的工作表路径");
        return value;
    }

    private static ZipEntry entry(ZipFile zip, String name) {
        return zip.getEntry(name);
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        return factory;
    }

    private static void setXmlProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // JDK XMLInputFactory implementations differ; unsupported hardening flags are ignored.
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    record Inspection(List<SheetInspection> sheets, List<Issue> issues) {
        Inspection {
            sheets = sheets == null ? List.of() : List.copyOf(sheets);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    record SheetInspection(String sheetName, int headerRowNumber, long rowCount, int columnCount,
                           long attachmentReferenceCount, List<String> headers,
                           FeishuImportTableCatalog.Match catalogMatch,
                           List<Map<String, String>> sampleRows,
                           List<RowInspection> rows) {
        SheetInspection {
            headers = headers == null ? List.of() : List.copyOf(headers);
            sampleRows = sampleRows == null ? List.of() : List.copyOf(sampleRows);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    record RowInspection(int rowNumber, Map<String, String> values,
                         Map<String, List<String>> attachmentRefs) {
        RowInspection {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachmentRefs = attachmentRefs == null ? Map.of() : Map.copyOf(attachmentRefs);
        }
    }

    record Issue(String severity, String issueType, String tableName,
                 Integer rowNumber, String fieldName, String message) {
    }

    private record WorkbookSheet(int index, String name, String path) {
    }
}
