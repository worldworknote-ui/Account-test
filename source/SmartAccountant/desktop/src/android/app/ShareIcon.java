package android.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 12.0: أيقونات قائمة المشاركة على الكمبيوتر، مرسومة بالخطوط (لا تعتمد على رموز قد لا يحويها الخط في الماك).
 * مربع مستدير بلون التطبيق، وفيه رسم أبيض بسيط. المقاسات على شبكة 48×48 وتُكبَّر لحجم العرض.
 */
final class ShareIcon extends View {
    private final String kind;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);

    ShareIcon(Context c, String kind) {
        super(c);
        this.kind = kind;
        fill.setColor(color(kind));
        white.setColor(0xFFFFFFFF);
        line.setColor(0xFFFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    static int color(String kind) {
        switch (kind) {
            case ShareSheet.WA_APP:
            case ShareSheet.WA_DRAG: return 0xFF25D366;
            case ShareSheet.WA_WEB: return 0xFF128C7E;
            case ShareSheet.TG: return 0xFF2AABEE;
            case ShareSheet.SYSTEM: return 0xFF5B6B8C;
            case ShareSheet.MAIL: return 0xFF3B82F6;
            case ShareSheet.COPY: return 0xFF64748B;
            case ShareSheet.SAVE: return 0xFF0B6B53;
            case ShareSheet.OPEN: return 0xFF8B5CF6;
            case ShareSheet.REVEAL: return 0xFFF59E0B;
            default: return 0xFF888888;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 48f;
        float ox = (getWidth() - 48 * s) / 2f, oy = (getHeight() - 48 * s) / 2f;
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        c.drawRoundRect(new RectF(0, 0, 48, 48), 14, 14, fill);
        line.setStrokeWidth(2.6f);
        Path p = new Path();
        switch (kind) {
            case ShareSheet.WA_APP:
            case ShareSheet.WA_WEB: {
                // فقاعة محادثة بذيل وسماعة صغيرة
                c.drawCircle(24, 23, 12, line);
                p.moveTo(15, 31);
                p.lineTo(12.5f, 37);
                p.lineTo(19, 34.5f);
                c.drawPath(p, line);
                Path h = new Path();
                h.moveTo(19.5f, 18.5f);
                h.quadTo(19, 22, 22.5f, 25.5f);
                h.quadTo(26, 29, 29.5f, 28.5f);
                line.setStrokeWidth(3.2f);
                c.drawPath(h, line);
                if (kind.equals(ShareSheet.WA_WEB)) {
                    // شارة «ويب»: كرة صغيرة في الزاوية
                    c.drawCircle(38, 38, 7.5f, fill);
                    line.setStrokeWidth(1.6f);
                    c.drawCircle(38, 38, 6, line);
                    c.drawLine(32, 38, 44, 38, line);
                    c.drawOval(new RectF(35.5f, 32, 40.5f, 44), line);
                }
                break;
            }
            case ShareSheet.WA_DRAG: {
                // مجلد وسهم سحب
                p.moveTo(10, 16);
                p.lineTo(20, 16);
                p.lineTo(23, 19);
                p.lineTo(38, 19);
                p.lineTo(38, 34);
                p.lineTo(10, 34);
                p.close();
                c.drawPath(p, line);
                Path a = new Path();
                a.moveTo(24, 22);
                a.lineTo(24, 30);
                a.moveTo(20, 26.5f);
                a.lineTo(24, 30.5f);
                a.lineTo(28, 26.5f);
                c.drawPath(a, line);
                break;
            }
            case ShareSheet.TG: {
                // طائرة ورقية
                p.moveTo(11, 23);
                p.lineTo(37, 13);
                p.lineTo(32, 36);
                p.lineTo(24, 29);
                p.close();
                c.drawPath(p, white);
                Path f = new Path();
                f.moveTo(37, 13);
                f.lineTo(21, 27);
                f.lineTo(22, 34);
                f.lineTo(24, 29);
                line.setColor(color(kind));
                line.setStrokeWidth(1.6f);
                c.drawPath(f, line);
                line.setColor(0xFFFFFFFF);
                break;
            }
            case ShareSheet.SYSTEM: {
                // رمز المشاركة: ثلاث نقاط متصلة
                c.drawLine(17, 24, 31, 16, line);
                c.drawLine(17, 24, 31, 32, line);
                c.drawCircle(16, 24, 4.2f, white);
                c.drawCircle(32, 15.5f, 4.2f, white);
                c.drawCircle(32, 32.5f, 4.2f, white);
                break;
            }
            case ShareSheet.MAIL: {
                c.drawRoundRect(new RectF(11, 15, 37, 33), 3, 3, line);
                p.moveTo(12, 17);
                p.lineTo(24, 26);
                p.lineTo(36, 17);
                c.drawPath(p, line);
                break;
            }
            case ShareSheet.COPY: {
                c.drawRoundRect(new RectF(14, 11, 30, 29), 3, 3, line);
                c.drawRoundRect(new RectF(19, 18, 35, 37), 3, 3, fill);
                c.drawRoundRect(new RectF(19, 18, 35, 37), 3, 3, line);
                break;
            }
            case ShareSheet.SAVE: {
                p.moveTo(24, 11);
                p.lineTo(24, 28);
                p.moveTo(17, 21);
                p.lineTo(24, 28);
                p.lineTo(31, 21);
                p.moveTo(12, 30);
                p.lineTo(12, 36);
                p.lineTo(36, 36);
                p.lineTo(36, 30);
                c.drawPath(p, line);
                break;
            }
            case ShareSheet.OPEN: {
                p.moveTo(15, 11);
                p.lineTo(28, 11);
                p.lineTo(34, 17);
                p.lineTo(34, 37);
                p.lineTo(15, 37);
                p.close();
                c.drawPath(p, line);
                c.drawLine(19, 24, 30, 24, line);
                c.drawLine(19, 30, 30, 30, line);
                break;
            }
            case ShareSheet.REVEAL: {
                p.moveTo(10, 15);
                p.lineTo(20, 15);
                p.lineTo(23, 18);
                p.lineTo(38, 18);
                p.lineTo(38, 34);
                p.lineTo(10, 34);
                p.close();
                c.drawPath(p, line);
                c.drawCircle(25, 26, 4, line);
                c.drawLine(28, 29, 32, 33, line);
                break;
            }
            default:
                c.drawCircle(24, 24, 6, white);
        }
        c.restore();
    }
}
