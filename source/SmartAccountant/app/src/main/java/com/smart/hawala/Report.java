package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;

/**
 * نموذج تقرير موحّد تُبنى منه كل الملفات المصدّرة (PDF، صور PNG، Excel).
 * كشف المكتب، أرصدة كل المكاتب، التقرير اليومي، وسجل الحركات كلها تُبنى بنفس النموذج،
 * فيكون شكلها وحساباتها متسقة في كل الصيغ.
 */
public final class Report {

    public static final int TEXT = 0, MONEY = 1, BAL = 2, DATE = 3, INT = 4;
    public static final int NORMAL = 0, SHADED = 1, BOLD = 2, MUTED = 3, TOTAL = 4;

    public String title = "";
    public final List<String> headerLines = new ArrayList<>();
    public String footer = "";
    /** سطر ظاهر في كل صفحة/ورقة للنسخة المجانية (فارغ في المرخّصة). */
    public String watermark = "";
    /** رقم الرخصة يُضمَّن في خصائص الملف (المرخّصة)، فيُعرف مصدر أي ملف مسرّب. */
    public String licenseId = "";
    public boolean signature;
    public String fileBase = "report";
    public final List<Section> sections = new ArrayList<>();

    /** مبلغ بالوحدة الصغرى مع منازله العشرية. */
    public static final class Money {
        public final long v;
        public final int dec;

        public Money(long v, int dec) {
            this.v = v;
            this.dec = dec;
        }

        @Override public String toString() { return Model.fmt(v, dec); }
    }

    /** رصيد يُعرض «لنا X» أو «علينا Y». */
    public static final class Bal {
        public final long v;
        public final int dec;

        public Bal(long v, int dec) {
            this.v = v;
            this.dec = dec;
        }

        @Override public String toString() { return Model.bal(v, dec); }
    }

    public static final class DateV {
        public final long t;

        public DateV(long t) { this.t = t; }

        @Override public String toString() { return Model.date(t); }
    }

    public static final class Col {
        public final String title;
        public final float weight;
        public final int type;

        public Col(String title, float weight, int type) {
            this.title = title;
            this.weight = weight;
            this.type = type;
        }
    }

    public static final class Row {
        public final Object[] cells;
        public int style = NORMAL;
        public List<Long> images;
        /** لون العملة لهذا الصف (ARGB) أو 0: خلفية خفيفة وأرقام بلون العملة حتى لا تختلط العملات على القارئ. */
        public int tint;

        public Row(int style, Object... cells) {
            this.style = style;
            this.cells = cells;
        }
    }

    public static final class Kv {
        public final String label;
        public final Object value;
        public final boolean emphasis;

        public Kv(String label, Object value, boolean emphasis) {
            this.label = label;
            this.value = value;
            this.emphasis = emphasis;
        }
    }

    public static final class Section {
        /** لون القسم (رأس الجدول وشريط العنوان) لقسم عملة واحدة، أو 0 = لون الكشف. */
        public int accent;
        public String heading = "";
        public String sheetName = "";
        public String note = "";
        public final List<Kv> summary = new ArrayList<>();
        public final List<Col> cols = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();

        public Section col(String title, float weight, int type) {
            cols.add(new Col(title, weight, type));
            return this;
        }

        public Row row(int style, Object... cells) {
            Row r = new Row(style, cells);
            rows.add(r);
            return r;
        }

        public Section kv(String label, Object value, boolean emphasis) {
            summary.add(new Kv(label, value, emphasis));
            return this;
        }
    }

    public Section section(String heading, String sheetName) {
        Section s = new Section();
        s.heading = heading;
        s.sheetName = sheetName;
        sections.add(s);
        return s;
    }

    /** نص الخلية كما يظهر في PDF/PNG. */
    public static String text(Object v) {
        if (v == null) return "";
        return v.toString();
    }

    public static final String FREE_MARK = "نسخة مجانية — المحاسب الذكي";

    /** علامة النسخة المجانية أو بصمة الرخصة، قبل الكتابة بأي صيغة. */
    public void stamp(boolean watermark, String lid) {
        this.watermark = watermark ? FREE_MARK : "";
        this.licenseId = watermark || lid == null ? "" : lid;
    }

    /** عدد صفوف البيانات في كل الأقسام (للتأكد أن التقرير ليس فارغًا). */
    public int dataRows() {
        int n = 0;
        for (Section s : sections) n += s.rows.size();
        return n;
    }
}
