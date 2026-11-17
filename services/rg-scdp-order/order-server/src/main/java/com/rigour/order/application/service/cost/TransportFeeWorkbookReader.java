package com.rigour.order.application.service.cost;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.zip.*;

import javax.xml.stream.*;

/** 只读取首张工作表的文本单号与金额；不执行公式，不接受丢失精度的长数字。 */
public final class TransportFeeWorkbookReader {
    public record Row(
            int line, String trackingNo, BigDecimal amount, String remark, String problem) {}

    private record Cell(String value, String type, boolean formula) {}

    private TransportFeeWorkbookReader() {}

    public static List<Row> read(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
            throw bad("请选择不超过10MB的xlsx文件");
        try {
            Map<String, byte[]> parts = new HashMap<>();
            long expanded = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (parts.size() >= 2000 || parts.containsKey(e.getName()))
                        throw bad("Excel文件结构无效");
                    var out = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = zip.read(b)) != -1; ) {
                        expanded += n;
                        if (expanded > 30L * 1024 * 1024) throw bad("Excel展开内容过大，请缩小文件");
                        out.write(b, 0, n);
                    }
                    parts.put(e.getName(), out.toByteArray());
                }
            }
            var book = xml(parts.get("xl/workbook.xml"));
            String sheetId = null;
            while (book.hasNext())
                if (book.next() == XMLStreamConstants.START_ELEMENT
                        && book.getLocalName().equals("sheet")) {
                    sheetId =
                            book.getAttributeValue(
                                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                    "id");
                    break;
                }
            book.close();
            var rels = xml(parts.get("xl/_rels/workbook.xml.rels"));
            String target = null;
            while (rels.hasNext())
                if (rels.next() == XMLStreamConstants.START_ELEMENT
                        && rels.getLocalName().equals("Relationship")
                        && Objects.equals(sheetId, rels.getAttributeValue(null, "Id"))) {
                    if ("External".equals(rels.getAttributeValue(null, "TargetMode")))
                        throw bad("不支持外部工作表");
                    target = rels.getAttributeValue(null, "Target");
                    break;
                }
            rels.close();
            if (target == null || target.contains("..")) throw bad("未找到首张工作表");
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            var strings = new ArrayList<String>();
            if (parts.containsKey("xl/sharedStrings.xml")) {
                var shared = xml(parts.get("xl/sharedStrings.xml"));
                StringBuilder text = null;
                while (shared.hasNext()) {
                    int event = shared.next();
                    if (event == XMLStreamConstants.START_ELEMENT
                            && shared.getLocalName().equals("si")) text = new StringBuilder();
                    else if (event == XMLStreamConstants.START_ELEMENT
                            && shared.getLocalName().equals("t")
                            && text != null) text.append(shared.getElementText());
                    else if (event == XMLStreamConstants.END_ELEMENT
                            && shared.getLocalName().equals("si")) {
                        strings.add(text.toString());
                        text = null;
                    }
                }
                shared.close();
            }
            var sheet = xml(parts.get(path));
            var rows = new LinkedHashMap<Integer, Map<Integer, Cell>>();
            int line = 0, col = 0;
            String type = null, value = "";
            boolean formula = false;
            while (sheet.hasNext()) {
                int event = sheet.next();
                if (event == XMLStreamConstants.START_ELEMENT)
                    switch (sheet.getLocalName()) {
                        case "mergeCell" -> throw bad("请取消合并单元格后重新导入");
                        case "row" -> {
                            line = Integer.parseInt(sheet.getAttributeValue(null, "r"));
                            if (line < 1 || line > 501 || rows.put(line, new HashMap<>()) != null)
                                throw bad("一次最多导入500条明细，请使用模板首行作为表头");
                        }
                        case "c" -> {
                            String ref = sheet.getAttributeValue(null, "r");
                            col = 0;
                            if (ref == null) throw bad("Excel单元格位置无效");
                            for (char ch : ref.toCharArray()) {
                                if (ch < 'A' || ch > 'Z') break;
                                col = col * 26 + ch - 'A' + 1;
                            }
                            if (col < 1 || col > 20) throw bad("请使用导入模板，不超过20列");
                            type = sheet.getAttributeValue(null, "t");
                            value = "";
                            formula = false;
                        }
                        case "f" -> formula = true;
                        case "v", "t" -> value += sheet.getElementText();
                    }
                else if (event == XMLStreamConstants.END_ELEMENT
                        && sheet.getLocalName().equals("c")) {
                    if ("s".equals(type)) value = strings.get(Integer.parseInt(value));
                    if (rows.get(line).put(col, new Cell(value.strip(), type, formula)) != null)
                        throw bad("Excel存在重复单元格");
                }
            }
            sheet.close();
            var header = rows.remove(1);
            if (header == null) throw bad("首行须为表头：快递单号、运费（元）、备注");
            var columns = new HashMap<String, Integer>();
            for (var e : header.entrySet()) {
                if (e.getValue().formula()) throw bad("表头不能使用公式");
                String name = e.getValue().value();
                if (!name.isEmpty() && columns.put(name, e.getKey()) != null) throw bad("表头不能重复");
            }
            if (!columns.containsKey("快递单号") || !columns.containsKey("运费（元）"))
                throw bad("缺少表头：快递单号、运费（元）");
            var result = new ArrayList<Row>();
            for (var entry : rows.entrySet()) {
                var cells = entry.getValue();
                if (cells.values().stream().allMatch(c -> c.value().isEmpty() && !c.formula()))
                    continue;
                Cell no = cells.getOrDefault(columns.get("快递单号"), new Cell("", null, false));
                Cell fee = cells.getOrDefault(columns.get("运费（元）"), new Cell("", null, false));
                Cell note = cells.getOrDefault(columns.get("备注"), new Cell("", null, false));
                String problem = null;
                BigDecimal amount = null;
                if (cells.values().stream().anyMatch(Cell::formula)) problem = "不支持公式，请粘贴为文本或数值";
                else if (no.value().isEmpty()
                        || no.value().length() > 80
                        || no.value().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)[eE][+-]?[0-9]+")
                        || (no.type() == null || "n".equals(no.type()))
                                && !no.value().matches("[0-9]{1,15}"))
                    problem = "请填写完整文本快递单号；长单号不能使用数字格式";
                else if (!fee.value().matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
                    problem = "运费须为非负金额，最多两位小数";
                else {
                    amount = new BigDecimal(fee.value()).setScale(2);
                    if (amount.signum() == 0 && note.value().isBlank()) problem = "零元运费请在备注说明原因";
                    else if (note.value().length() > 500) problem = "备注最多500字";
                }
                result.add(new Row(entry.getKey(), no.value(), amount, note.value(), problem));
            }
            if (result.isEmpty()) throw bad("Excel没有可导入的明细");
            return List.copyOf(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw bad("无法读取xlsx文件，请使用模板并检查文件是否损坏");
        }
    }

    /** 附于单笔运输的凭证只含本行；完整原件另行留存，不向其他单据的查看人公开。 */
    static byte[] evidence(Row row, String originalName) {
        String[][] values = {
            {"快递单号", "运费（元）", "备注", "来源文件", "原文件行号"},
            {
                row.trackingNo(),
                row.amount().toPlainString(),
                row.remark(),
                originalName,
                Integer.toString(row.line())
            }
        };
        var sheet =
                new StringBuilder(
                        "<worksheet"
                            + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < values.length; r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < values[r].length; c++)
                sheet.append("<c r=\"")
                        .append((char) ('A' + c))
                        .append(r + 1)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xmlText(values[r][c]))
                        .append("</t></is></c>");
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        var parts =
                Map.of(
                        "[Content_Types].xml",
                                "<Types"
                                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                                    + " Extension=\"rels\""
                                    + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                                    + " Extension=\"xml\""
                                    + " ContentType=\"application/xml\"/><Override"
                                    + " PartName=\"/xl/workbook.xml\""
                                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
                                    + " PartName=\"/xl/worksheets/sheet1.xml\""
                                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>",
                        "_rels/.rels",
                                "<Relationships"
                                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                                    + " Id=\"rId1\""
                                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                                    + " Target=\"xl/workbook.xml\"/></Relationships>",
                        "xl/workbook.xml",
                                "<workbook"
                                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                                    + " name=\"运费补录凭证\" sheetId=\"1\""
                                    + " r:id=\"rId1\"/></sheets></workbook>",
                        "xl/_rels/workbook.xml.rels",
                                "<Relationships"
                                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                                    + " Id=\"rId1\""
                                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                                    + " Target=\"worksheets/sheet1.xml\"/></Relationships>",
                        "xl/worksheets/sheet1.xml", sheet.toString());
        try {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                for (var e : parts.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String xmlText(String value) {
        var cleaned = new StringBuilder();
        value.codePoints()
                .filter(
                        c ->
                                c == 9
                                        || c == 10
                                        || c == 13
                                        || c >= 32 && c <= 0xD7FF
                                        || c >= 0xE000 && c <= 0xFFFD
                                        || c >= 0x10000 && c <= 0x10FFFF)
                .forEach(cleaned::appendCodePoint);
        return cleaned.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        if (bytes == null) throw bad("Excel缺少必要的工作表内容");
        var f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        f.setXMLResolver(
                (a, b, c, d) -> {
                    throw new XMLStreamException("外部实体被禁止");
                });
        return f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static BusinessException bad(String text) {
        return new BusinessException(ErrorCode.BAD_REQUEST, text, List.of());
    }
}
