package com.smart.hawala.desk;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/** أيقونات خطية بسيطة مرسومة (لا ملفات صور): تبقى حادة على شاشات Retina. */
final class Icons implements Icon {
    final String key;
    final int size;
    Color color;

    Icons(String key, int size, Color color) {
        this.key = key;
        this.size = size;
        this.color = color;
    }

    @Override public int getIconWidth() { return size; }

    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.translate(x, y);
        float s = size / 24f;
        g2.scale(s, s);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        draw(g2);
        g2.dispose();
    }

    private void draw(Graphics2D g) {
        switch (key) {
            case "moon": {
                Area m = new Area(new Ellipse2D.Float(4, 4, 16, 16));
                m.subtract(new Area(new Ellipse2D.Float(9, 1.5f, 14, 14)));
                g.draw(m);
                break;
            }
            case "sun": {
                g.draw(new Ellipse2D.Float(8, 8, 8, 8));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    g.draw(new Line2D.Double(12 + Math.cos(a) * 6.5, 12 + Math.sin(a) * 6.5, 12 + Math.cos(a) * 9, 12 + Math.sin(a) * 9));
                }
                break;
            }
            case "home": {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 11);
                p.lineTo(12, 3.5);
                p.lineTo(21, 11);
                g.draw(p);
                g.draw(new RoundRectangle2D.Float(5.5f, 10, 13, 10.5f, 2, 2));
                g.draw(new Line2D.Float(10, 20.5f, 10, 15));
                g.draw(new Line2D.Float(14, 20.5f, 14, 15));
                g.draw(new Line2D.Float(10, 15, 14, 15));
                break;
            }
            // 10.6: أيقونات قائمة «تسجيل»
            case "plus": {
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Float(12, 5, 12, 19));
                g.draw(new Line2D.Float(5, 12, 19, 12));
                break;
            }
            case "close": {
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Float(6.5f, 6.5f, 17.5f, 17.5f));
                g.draw(new Line2D.Float(17.5f, 6.5f, 6.5f, 17.5f));
                break;
            }
            case "fx": {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                Path2D p = new Path2D.Float();
                p.moveTo(14.8f, 8.6f);
                p.curveTo(14.2f, 7.6f, 13.2f, 7.2f, 12, 7.2f);
                p.curveTo(10.2f, 7.2f, 9.2f, 8.1f, 9.2f, 9.4f);
                p.curveTo(9.2f, 12.3f, 14.8f, 11.3f, 14.8f, 14.4f);
                p.curveTo(14.8f, 15.8f, 13.6f, 16.8f, 12, 16.8f);
                p.curveTo(10.6f, 16.8f, 9.6f, 16.2f, 9.1f, 15.3f);
                g.draw(p);
                g.draw(new Line2D.Float(12, 5.4f, 12, 7.2f));
                g.draw(new Line2D.Float(12, 16.8f, 12, 18.6f));
                break;
            }
            case "xfer": {
                g.draw(new Line2D.Float(4, 8.5f, 19, 8.5f));
                Path2D a = new Path2D.Float();
                a.moveTo(15.5f, 5);
                a.lineTo(19, 8.5f);
                a.lineTo(15.5f, 12);
                g.draw(a);
                g.draw(new Line2D.Float(20, 15.5f, 5, 15.5f));
                Path2D b = new Path2D.Float();
                b.moveTo(8.5f, 12);
                b.lineTo(5, 15.5f);
                b.lineTo(8.5f, 19);
                g.draw(b);
                break;
            }
            case "tx": {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.draw(new Line2D.Float(12, 7.5f, 12, 16.5f));
                g.draw(new Line2D.Float(7.5f, 12, 16.5f, 12));
                break;
            }
            case "offices": {
                g.draw(new RoundRectangle2D.Float(4, 6, 9, 15, 1.5f, 1.5f));
                g.draw(new RoundRectangle2D.Float(13, 10, 7, 11, 1.5f, 1.5f));
                for (int i = 0; i < 3; i++) g.draw(new Line2D.Float(7, 10 + i * 3.5f, 10, 10 + i * 3.5f));
                g.draw(new Line2D.Float(16, 14, 17, 14));
                g.draw(new Line2D.Float(16, 17.5f, 17, 17.5f));
                break;
            }
            case "log": {
                for (int i = 0; i < 4; i++) {
                    float yy = 5.5f + i * 4.5f;
                    g.fill(new Ellipse2D.Float(3.5f, yy - 1.2f, 2.4f, 2.4f));
                    g.draw(new Line2D.Float(9, yy, 20.5f, yy));
                }
                break;
            }
            case "statement": {
                Path2D p = new Path2D.Float();
                p.moveTo(6, 3);
                p.lineTo(15, 3);
                p.lineTo(19, 7);
                p.lineTo(19, 21);
                p.lineTo(6, 21);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Float(9, 11, 16, 11));
                g.draw(new Line2D.Float(9, 14.5f, 16, 14.5f));
                g.draw(new Line2D.Float(9, 18, 13, 18));
                break;
            }
            case "balances": {
                g.draw(new Line2D.Float(12, 4, 12, 20));
                g.draw(new Line2D.Float(6, 20, 18, 20));
                g.draw(new Line2D.Float(4, 7, 20, 7));
                Path2D l = new Path2D.Float();
                l.moveTo(4, 7);
                l.lineTo(2, 13);
                l.quadTo(4, 15.5f, 6, 13);
                l.closePath();
                g.draw(l);
                Path2D r = new Path2D.Float();
                r.moveTo(20, 7);
                r.lineTo(18, 13);
                r.quadTo(20, 15.5f, 22, 13);
                r.closePath();
                g.draw(r);
                break;
            }
            case "daily": {
                g.draw(new RoundRectangle2D.Float(3.5f, 5, 17, 15.5f, 3, 3));
                g.draw(new Line2D.Float(3.5f, 9.5f, 20.5f, 9.5f));
                g.draw(new Line2D.Float(8, 3, 8, 6.5f));
                g.draw(new Line2D.Float(16, 3, 16, 6.5f));
                g.fill(new RoundRectangle2D.Float(7, 12.5f, 3.5f, 3.5f, 1, 1));
                break;
            }
            case "reports": {
                g.draw(new Line2D.Float(4, 20, 20, 20));
                g.draw(new RoundRectangle2D.Float(5.5f, 12, 3, 8, 1, 1));
                g.draw(new RoundRectangle2D.Float(10.5f, 7, 3, 13, 1, 1));
                g.draw(new RoundRectangle2D.Float(15.5f, 10, 3, 10, 1, 1));
                break;
            }
            case "builder": {
                for (int i = 0; i < 3; i++) {
                    float yy = 6 + i * 6;
                    g.draw(new Line2D.Float(4, yy, 20, yy));
                    float kx = i == 0 ? 15 : i == 1 ? 8 : 13;
                    g.setColor(Color.WHITE);
                    g.fill(new Ellipse2D.Float(kx - 2.3f, yy - 2.3f, 4.6f, 4.6f));
                    g.setColor(color);
                    g.draw(new Ellipse2D.Float(kx - 2.3f, yy - 2.3f, 4.6f, 4.6f));
                }
                break;
            }
            case "owner": {
                Path2D p = new Path2D.Float();
                p.moveTo(4, 18);
                p.lineTo(3, 8);
                p.lineTo(8.5f, 12);
                p.lineTo(12, 5);
                p.lineTo(15.5f, 12);
                p.lineTo(21, 8);
                p.lineTo(20, 18);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Float(4, 21, 20, 21));
                break;
            }
            case "backup": {
                Path2D p = new Path2D.Float();
                p.moveTo(7, 18);
                p.curveTo(2, 18, 2, 11, 7, 11);
                p.curveTo(7.5f, 5, 16, 4.5f, 17, 10);
                p.curveTo(22, 10, 22, 18, 17, 18);
                g.draw(p);
                g.draw(new Line2D.Float(12, 21, 12, 12));
                Path2D a = new Path2D.Float();
                a.moveTo(9, 15);
                a.lineTo(12, 12);
                a.lineTo(15, 15);
                g.draw(a);
                break;
            }
            case "settings": {
                g.draw(new Ellipse2D.Float(8.5f, 8.5f, 7, 7));
                for (int i = 0; i < 8; i++) {
                    double t = Math.PI * i / 4;
                    g.draw(new Line2D.Double(12 + Math.cos(t) * 6.2, 12 + Math.sin(t) * 6.2, 12 + Math.cos(t) * 9, 12 + Math.sin(t) * 9));
                }
                g.draw(new Ellipse2D.Float(5.8f, 5.8f, 12.4f, 12.4f));
                break;
            }
            case "users": {
                g.draw(new Ellipse2D.Float(8, 3.5f, 8, 8));
                Path2D p = new Path2D.Float();
                p.moveTo(4, 20.5f);
                p.curveTo(4, 14, 20, 14, 20, 20.5f);
                g.draw(p);
                break;
            }
            case "license": {
                g.draw(new Ellipse2D.Float(3, 8, 8, 8));
                g.draw(new Line2D.Float(11, 12, 21, 12));
                g.draw(new Line2D.Float(17, 12, 17, 15.5f));
                g.draw(new Line2D.Float(20, 12, 20, 14.5f));
                break;
            }
            case "health": {
                Path2D p = new Path2D.Float();
                p.moveTo(2.5f, 12.5f);
                p.lineTo(7, 12.5f);
                p.lineTo(9.5f, 7);
                p.lineTo(13, 18);
                p.lineTo(15.5f, 12.5f);
                p.lineTo(21.5f, 12.5f);
                g.draw(p);
                break;
            }
            case "share": {
                g.draw(new Ellipse2D.Float(15, 3, 6, 6));
                g.draw(new Ellipse2D.Float(3, 9, 6, 6));
                g.draw(new Ellipse2D.Float(15, 15, 6, 6));
                g.draw(new Line2D.Float(8.5f, 10.5f, 15.5f, 7));
                g.draw(new Line2D.Float(8.5f, 13.5f, 15.5f, 17));
                break;
            }
            case "pin": {
                Path2D p = new Path2D.Float();
                p.moveTo(9, 3);
                p.lineTo(15, 3);
                p.moveTo(10, 3);
                p.lineTo(10, 10);
                p.lineTo(7, 14);
                p.lineTo(17, 14);
                p.lineTo(14, 10);
                p.lineTo(14, 3);
                p.moveTo(12, 14);
                p.lineTo(12, 21);
                g.draw(p);
                break;
            }
            case "more": {
                for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Float(10.5f, 4.5f + i * 6, 3, 3));
                break;
            }
            case "recon": {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                Path2D p = new Path2D.Float();
                p.moveTo(7.5f, 12.5f);
                p.lineTo(10.5f, 15.5f);
                p.lineTo(16.5f, 9);
                g.draw(p);
                break;
            }
            case "lock": {
                g.draw(new RoundRectangle2D.Float(5, 10.5f, 14, 10, 3, 3));
                Path2D p = new Path2D.Float();
                p.moveTo(8, 10.5f);
                p.lineTo(8, 8);
                p.curveTo(8, 2.5f, 16, 2.5f, 16, 8);
                p.lineTo(16, 10.5f);
                g.draw(p);
                break;
            }
            case "search": {
                g.draw(new Ellipse2D.Float(4, 4, 12, 12));
                g.draw(new Line2D.Float(14.5f, 14.5f, 20, 20));
                break;
            }
            case "cash": {
                // صندوق نقد بدرج
                g.draw(new RoundRectangle2D.Float(3, 10, 18, 10.5f, 3, 3));
                Path2D p = new Path2D.Float();
                p.moveTo(5, 10);
                p.lineTo(6.5f, 4.5f);
                p.lineTo(17.5f, 4.5f);
                p.lineTo(19, 10);
                g.draw(p);
                g.draw(new Line2D.Float(9.5f, 7.5f, 14.5f, 7.5f));
                g.draw(new Line2D.Float(3, 14, 21, 14));
                g.draw(new Line2D.Float(10.5f, 17, 13.5f, 17));
                break;
            }
            case "clients": {
                g.draw(new Ellipse2D.Float(5, 4, 7, 7));
                Path2D p = new Path2D.Float();
                p.moveTo(2.5f, 20);
                p.curveTo(3, 15, 6, 13, 8.5f, 13);
                p.curveTo(11, 13, 14, 15, 14.5f, 20);
                g.draw(p);
                g.draw(new Ellipse2D.Float(14, 5.5f, 5.5f, 5.5f));
                Path2D q = new Path2D.Float();
                q.moveTo(16, 13.2f);
                q.curveTo(19, 13.5f, 21, 15.5f, 21.5f, 19);
                g.draw(q);
                break;
            }
            case "sync": {
                g.draw(new java.awt.geom.Arc2D.Float(4, 4, 16, 16, 30, 150, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(4, 4, 16, 16, 210, 150, java.awt.geom.Arc2D.OPEN));
                g.draw(new Line2D.Float(4.6f, 8.5f, 4.2f, 4.2f));
                g.draw(new Line2D.Float(4.6f, 8.5f, 8.8f, 8.2f));
                g.draw(new Line2D.Float(19.4f, 15.5f, 19.8f, 19.8f));
                g.draw(new Line2D.Float(19.4f, 15.5f, 15.2f, 15.8f));
                break;
            }
            case "mine": {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                Path2D.Float ck = new Path2D.Float();
                ck.moveTo(7.5f, 12.5f);
                ck.lineTo(10.5f, 15.5f);
                ck.lineTo(16.5f, 8.5f);
                g.draw(ck);
                break;
            }
            case "about": {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.draw(new Line2D.Float(12, 11, 12, 16.5f));
                g.fill(new Ellipse2D.Float(11, 7, 2, 2));
                break;
            }
            default:
                g.draw(new Ellipse2D.Float(6, 6, 12, 12));
        }
    }
}
