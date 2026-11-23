package com.smart.hawala;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * كاتب ملفات Excel حقيقية (.xlsx) بدون أي مكتبة خارجية.
 * كل قسم من التقرير في ورقة مستقلة، الورقة من اليمين لليسار، الأرقام أرقام حقيقية (تُجمع وتُرتب في Excel)،
 * والأرصدة تُعرض «لنا/علينا» عبر تنسيق رقمي مع بقاء القيمة رقمًا.
 */
public final class XlsxWriter {

    // ---------------------------------------------------------------------------- الأنماط
    private static final int F_NORMAL = 0, F_BOLD = 1, F_TITLE = 2, F_SUBTLE = 3, F_HEADER = 4;
    private static final int FILL_NONE = 0, FILL_HEADER = 2, FILL_SHADE = 3, FILL_TOTAL = 4, FILL_LABEL = 5;

    private final Map<String, Integer> numFmts = new LinkedHashMap<>();
    private final Map<String, Integer> xfIndex = new LinkedHashMap<>();
    private final List<String> xfs = new ArrayList<>();
    private final String accent;
    /** ألوان إضافية (ألوان العملات): خلفيات وخطوط تُضاف بعد الثابتة. */
    private final List<String> fills = new ArrayList<>();
    private final List<String> fonts = new ArrayList<>();

    private static String argb(int c) { return String.format(java.util.Locale.US, "FF%06X", c & 0xFFFFFF); }

    static int blend(int c, float a) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (Math.round(255 - (255 - r) * a) << 16) | (Math.round(255 - (255 - g) * a) << 8) | Math.round(255 - (255 - b) * a);
    }

    static int darker(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (Math.round(r * 0.78f) << 16) | (Math.round(g * 0.78f) << 8) | Math.round(b * 0.78f);
    }

    private int fillOf(int color) {
        String k = argb(color);
        int i = fills.indexOf(k);
        if (i < 0) {
            fills.add(k);
            i = fills.size() - 1;
        }
        return 6 + i;
    }

    private int fontOf(int color, boolean white) {
        String k = (white ? "W" : "") + argb(color);
        int i = fonts.indexOf(k);
        if (i < 0) {
            fonts.add(k);
            i = fonts.size() - 1;
        }
        return 5 + i;
    }

    private XlsxWriter(String accentArgb) {
        accent = accentArgb;
        style(F_NORMAL, FILL_NONE, 0, null, null, false); // النمط 0 الافتراضي
    }

    private int numFmt(String code) {
        Integer id = numFmts.get(code);
        if (id == null) {
            id = 164 + numFmts.size();
            numFmts.put(code, id);
        }
        return id;
    }

    private int style(int font, int fill, int border, String fmt, String align, boolean wrap) {
        int nf = fmt == null ? 0 : numFmt(fmt);
        String key = font + "|" + fill + "|" + border + "|" + nf + "|" + align + "|" + wrap;
        Integer i = xfIndex.get(key);
        if (i != null) return i;
        StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(nf).append("\" fontId=\"").append(font).append("\" fillId=\"").append(fill)
                .append("\" borderId=\"").append(border).append("\" xfId=\"0\"");
        if (nf != 0) x.append(" applyNumberFormat=\"1\"");
        if (font != 0) x.append(" applyFont=\"1\"");
        if (fill != 0) x.append(" applyFill=\"1\"");
        if (border != 0) x.append(" applyBorder=\"1\"");
        if (align != null || wrap) {
            x.append(" applyAlignment=\"1\"><alignment");
            if (align != null) x.append(" horizontal=\"").append(align).append("\"");
            x.append(" vertical=\"top\"");
            if (wrap) x.append(" wrapText=\"1\"");
            x.append("/></xf>");
        } else x.append("/>");
        xfs.add(x.toString());
        xfIndex.put(key, xfs.size() - 1);
        return xfs.size() - 1;
    }

    static String moneyFmt(int dec) {
        StringBuilder f = new StringBuilder("#,##0");
        if (dec > 0) {
            f.append('.');
            for (int i = 0; i < dec; i++) f.append('0');
        }
        return f.toString();
    }

    static String balFmt(int dec) {
        String m = moneyFmt(dec);
        return "\"لنا \"" + m + ";\"علينا \"" + m + ";0";
    }

    private static final String DATE_FMT = "yyyy/mm/dd hh:mm";

    // ---------------------------------------------------------------------------- الكتابة

    public static void write(Report r, OutputStream out, String accentArgb, TimeZone tz) throws IOException {
        new XlsxWriter(accentArgb == null ? "FF0B6B53" : accentArgb).doWrite(r, out, tz == null ? TimeZone.getDefault() : tz);
    }

    private void doWrite(Report r, OutputStream out, TimeZone tz) throws IOException {
        List<String> sheetNames = new ArrayList<>();
        List<String> sheetXml = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (r.sections.isEmpty()) {
            Report.Section s = new Report.Section();
            s.heading = r.title;
            s.note = "لا توجد بيانات";
            r.sections.add(s);
        }
        int idx = 0;
        for (Report.Section s : r.sections) {
            idx++;
            String name = sheetName(s.sheetName == null || s.sheetName.isEmpty() ? "ورقة " + idx : s.sheetName, used, idx);
            sheetNames.add(name);
            sheetXml.add(sheet(r, s, idx == 1, tz));
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        put(zip, "[Content_Types].xml", contentTypes(sheetNames.size()));
        put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>");
        put(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + esc(r.title) + "</dc:title><dc:creator>المحاسب الذكي</dc:creator>"
                + (r.watermark.isEmpty() ? "" : "<dc:description>" + esc(r.watermark) + "</dc:description>")
                + (r.licenseId.isEmpty() ? "" : "<cp:keywords>SA-" + esc(r.licenseId) + "</cp:keywords>")
                + "</cp:coreProperties>");
        put(zip, "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Smart Accountant</Application></Properties>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView/></bookViews><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheetNames.size(); i++) {
            wb.append("<sheet name=\"").append(esc(sheetNames.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
            put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml.get(i));
        }
        wb.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rId").append(sheetNames.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        put(zip, "xl/workbook.xml", wb.toString());
        put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
        put(zip, "xl/styles.xml", styles());
        zip.finish();
        zip.flush();
    }

    private static String sheetName(String raw, Set<String> used, int idx) {
        String n = raw.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (n.length() > 28) n = n.substring(0, 28);
        if (n.isEmpty()) n = "ورقة " + idx;
        String base = n;
        int k = 2;
        while (used.contains(n.toLowerCase())) n = base + " " + (k++);
        used.add(n.toLowerCase());
        return n;
    }

    private String sheet(Report r, Report.Section s, boolean firstSheet, TimeZone tz) {
        int ncols = Math.max(2, s.cols.size());
        List<String> rows = new ArrayList<>();
        List<String> merges = new ArrayList<>();
        double[] width = new double[ncols];
        for (int i = 0; i < ncols; i++) width[i] = 10;
        int sTitle = style(F_TITLE, FILL_NONE, 0, null, "right", false);
        int sSub = style(F_SUBTLE, FILL_NONE, 0, null, "right", false);
        int sHead = s.accent != 0 ? style(F_HEADER, fillOf(s.accent), 1, null, "center", true) : style(F_HEADER, FILL_HEADER, 1, null, "center", true);
        int sLabel = style(F_BOLD, FILL_LABEL, 1, null, "right", false);
        int rn = 0;

        rn++;
        rows.add(rowXml(rn, cellStr(0, rn, firstSheet ? r.title : r.title + " — " + s.heading, sTitle)));
        merges.add(ref(0, rn) + ":" + ref(ncols - 1, rn));
        if (!r.watermark.isEmpty()) {
            rn++;
            rows.add(rowXml(rn, cellStr(0, rn, r.watermark, style(F_BOLD, FILL_NONE, 0, null, "right", false))));
            merges.add(ref(0, rn) + ":" + ref(ncols - 1, rn));
        }
        if (firstSheet) {
            for (String h : r.headerLines) {
                rn++;
                rows.add(rowXml(rn, cellStr(0, rn, h, sSub)));
                merges.add(ref(0, rn) + ":" + ref(ncols - 1, rn));
            }
        }
        if (firstSheet && !s.heading.isEmpty()) {
            rn++;
            rows.add(rowXml(rn, cellStr(0, rn, s.heading, style(F_BOLD, FILL_NONE, 0, null, "right", false))));
            merges.add(ref(0, rn) + ":" + ref(ncols - 1, rn));
        }
        for (Report.Kv kv : s.summary) {
            rn++;
            String v = valueCell(1, rn, kv.value, kv.emphasis ? F_BOLD : F_NORMAL, kv.emphasis ? FILL_TOTAL : FILL_NONE, 1, tz, width);
            rows.add(rowXml(rn, cellStr(0, rn, kv.label, sLabel) + v));
            width[0] = Math.max(width[0], len(kv.label));
        }
        if (!s.note.isEmpty()) {
            rn++;
            rows.add(rowXml(rn, cellStr(0, rn, s.note, sSub)));
            merges.add(ref(0, rn) + ":" + ref(ncols - 1, rn));
        }
        int freeze = 0;
        if (!s.cols.isEmpty()) {
            rn++;
            StringBuilder hr = new StringBuilder();
            for (int c = 0; c < s.cols.size(); c++) {
                hr.append(cellStr(c, rn, s.cols.get(c).title, sHead));
                width[c] = Math.max(width[c], len(s.cols.get(c).title) + 2);
            }
            rows.add(rowXml(rn, hr.toString()));
            freeze = rn;
            for (Report.Row row : s.rows) {
                rn++;
                int font = row.style == Report.TOTAL || row.style == Report.BOLD ? F_BOLD : (row.style == Report.MUTED ? F_SUBTLE : F_NORMAL);
                int fill = row.style == Report.SHADED ? FILL_SHADE : (row.style == Report.TOTAL ? FILL_TOTAL : FILL_NONE);
                int tint = row.tint != 0 ? row.tint : (row.style == Report.TOTAL ? s.accent : 0);
                int numFont = font;
                if (tint != 0 && row.style != Report.MUTED) {
                    // صف بلون عملته: خلفية خفيفة وأرقام بلون العملة
                    fill = fillOf(blend(tint, row.style == Report.TOTAL ? 0.22f : row.style == Report.SHADED ? 0.15f : 0.08f));
                    numFont = fontOf(darker(tint), false);
                }
                StringBuilder cells = new StringBuilder();
                for (int c = 0; c < s.cols.size() && c < row.cells.length; c++) {
                    Object v = row.cells[c];
                    if (v == null || (v instanceof String && ((String) v).isEmpty())) {
                        if (fill != FILL_NONE) cells.append("<c r=\"").append(ref(c, rn)).append("\" s=\"").append(style(font, fill, 1, null, null, false)).append("\"/>");
                        continue;
                    }
                    boolean num = v instanceof Report.Money || v instanceof Report.Bal;
                    cells.append(valueCell(c, rn, v, num ? numFont : font, fill, 1, tz, width));
                }
                rows.add(rowXml(rn, cells.toString()));
            }
        }
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        x.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"");
        if (firstSheet) x.append(" tabSelected=\"1\"");
        x.append(">");
        if (freeze > 0) {
            x.append("<pane ySplit=\"").append(freeze).append("\" topLeftCell=\"A").append(freeze + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        }
        x.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int c = 0; c < ncols; c++) {
            double w = Math.min(60, Math.max(8, width[c] * 1.15 + 2));
            x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(String.format(java.util.Locale.US, "%.1f", w)).append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");
        for (String row : rows) x.append(row);
        x.append("</sheetData>");
        if (!merges.isEmpty()) {
            x.append("<mergeCells count=\"").append(merges.size()).append("\">");
            for (String m : merges) x.append("<mergeCell ref=\"").append(m).append("\"/>");
            x.append("</mergeCells>");
        }
        x.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        x.append("<pageSetup paperSize=\"9\" orientation=\"").append(ncols > 6 ? "landscape" : "portrait").append("\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        if (!r.watermark.isEmpty()) x.append("<headerFooter><oddFooter>&amp;C").append(esc(r.watermark)).append("</oddFooter></headerFooter>");
        x.append("</worksheet>");
        return x.toString();
    }

    private String valueCell(int c, int rn, Object v, int font, int fill, int border, TimeZone tz, double[] width) {
        String r = ref(c, rn);
        if (v instanceof Report.Money) {
            Report.Money m = (Report.Money) v;
            track(width, c, Model.fmt(m.v, m.dec));
            return "<c r=\"" + r + "\" s=\"" + style(font, fill, border, moneyFmt(m.dec), "center", false) + "\"><v>" + BigDecimal.valueOf(m.v, m.dec).toPlainString() + "</v></c>";
        }
        if (v instanceof Report.Bal) {
            Report.Bal b = (Report.Bal) v;
            track(width, c, Model.bal(b.v, b.dec));
            return "<c r=\"" + r + "\" s=\"" + style(font, fill, border, balFmt(b.dec), "center", false) + "\"><v>" + BigDecimal.valueOf(b.v, b.dec).toPlainString() + "</v></c>";
        }
        if (v instanceof Report.DateV) {
            long t = ((Report.DateV) v).t;
            double serial = (t + tz.getOffset(t)) / 86400000.0 + 25569.0;
            track(width, c, "0000/00/00 00:00");
            return "<c r=\"" + r + "\" s=\"" + style(font, fill, border, DATE_FMT, "center", false) + "\"><v>" + String.format(java.util.Locale.US, "%.8f", serial) + "</v></c>";
        }
        if (v instanceof Long || v instanceof Integer) {
            track(width, c, String.valueOf(v));
            return "<c r=\"" + r + "\" s=\"" + style(font, fill, border, null, "center", false) + "\"><v>" + v + "</v></c>";
        }
        String s = String.valueOf(v);
        for (String line : s.split("\n")) track(width, c, line);
        return cellStr(c, rn, s, style(font, fill, border, null, "right", s.contains("\n")));
    }

    private static void track(double[] width, int c, String text) {
        if (c < width.length) width[c] = Math.max(width[c], len(text));
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    private static String cellStr(int c, int rn, String text, int style) {
        return "<c r=\"" + ref(c, rn) + "\" t=\"inlineStr\" s=\"" + style + "\"><is><t xml:space=\"preserve\">" + esc(text) + "</t></is></c>";
    }

    private static String rowXml(int rn, String cells) {
        return "<row r=\"" + rn + "\">" + cells + "</row>";
    }

    static String ref(int col, int row) {
        StringBuilder s = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            int m = (c - 1) % 26;
            s.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return s.toString() + row;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') continue; // محارف غير مسموحة في XML
                    if (ch == 0xFFFE || ch == 0xFFFF) continue;
                    b.append(ch);
            }
        }
        return b.toString();
    }

    private String styles() {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!numFmts.isEmpty()) {
            x.append("<numFmts count=\"").append(numFmts.size()).append("\">");
            for (Map.Entry<String, Integer> e : numFmts.entrySet()) {
                x.append("<numFmt numFmtId=\"").append(e.getValue()).append("\" formatCode=\"").append(esc(e.getKey())).append("\"/>");
            }
            x.append("</numFmts>");
        }
        x.append("<fonts count=\"").append(5 + fonts.size()).append("\">")
                .append("<font><sz val=\"11\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"11\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"14\"/><color rgb=\"").append(accent).append("\"/><name val=\"Arial\"/></font>")
                .append("<font><sz val=\"10\"/><color rgb=\"FF777777\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font>");
        for (String f : fonts) x.append("<font><b/><sz val=\"11\"/><color rgb=\"").append(f.startsWith("W") ? "FFFFFFFF" : f).append("\"/><name val=\"Arial\"/></font>");
        x.append("</fonts>");
        x.append("<fills count=\"").append(6 + fills.size()).append("\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
                .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"").append(accent).append("\"/><bgColor indexed=\"64\"/></patternFill></fill>")
                .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF6F3EC\"/><bgColor indexed=\"64\"/></patternFill></fill>")
                .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE3EFE9\"/><bgColor indexed=\"64\"/></patternFill></fill>")
                .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF1F1F1\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        for (String f : fills) x.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"").append(f).append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        x.append("</fills>");
        x.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
                .append("<border><left style=\"thin\"><color rgb=\"FFD9D9D9\"/></left><right style=\"thin\"><color rgb=\"FFD9D9D9\"/></right>")
                .append("<top style=\"thin\"><color rgb=\"FFD9D9D9\"/></top><bottom style=\"thin\"><color rgb=\"FFD9D9D9\"/></bottom><diagonal/></border></borders>");
        x.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        x.append("<cellXfs count=\"").append(xfs.size()).append("\">");
        for (String xf : xfs) x.append(xf);
        x.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        return x.toString();
    }

    private static String contentTypes(int sheets) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            x.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return x.append("</Types>").toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
