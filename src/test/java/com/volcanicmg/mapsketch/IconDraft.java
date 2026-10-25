package com.volcanicmg.mapsketch;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Draft rasterizer for the pixel icons ({@code ./gradlew iconDraft}): {@code icons/svg/*.svg} → 16x16 no-AA grids
 * fitted to the 12x12 content box, printed as a starting point that is then fixed by hand in {@code UiIconArt}.
 * Supports the SVG subset the sources use: path (M L H V C S Q T A Z, abs + rel), line, polyline, rect (rx), circle, g, transform, opacity, clip-path (clipPath of filled shapes, plus their stroke
 * when the clip shape names a stroke-width), and {@code data-erase} (fill clears ink: Excalidraw's white handles).
 * Strokes use round caps and joins. Output is deterministic: files sorted by name, fixed JDK rasterizer.
 */
public final class IconDraft {
    private IconDraft() {
    }

    /** @param args the svg dir; prints a 16x16 no-AA draft grid per icon */
    public static void main(String[] args) throws Exception {
        File[] files = new File(args[0]).listFiles((d, n) -> n.endsWith(".svg"));
        Arrays.sort(files);
        for (File f : files) {
            pixel = false;
            cell = 512;
            pad = 0;
            vbOverride = null;
            BufferedImage big = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            render(big, f, 0, 0);
            int x0 = 512, y0 = 512, x1 = -1, y1 = -1;
            for (int y = 0; y < 512; y++)
                for (int x = 0; x < 512; x++)
                    if ((big.getRGB(x, y) >>> 24) > 127) {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    }
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            double[] vb = nums(dbf.newDocumentBuilder().parse(f).getDocumentElement().getAttribute("viewBox"));
            double k = Math.max(vb[2], vb[3]) / 512, ox = (512 - vb[2] / k) / 2, oy = (512 - vb[3] / k) / 2;
            vbOverride = new double[]{vb[0] + (x0 - ox) * k, vb[1] + (y0 - oy) * k, (x1 - x0 + 1) * k, (y1 - y0 + 1) * k};
            pixel = true;
            cell = 16;
            pad = 2;
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            render(img, f, 0, 0);
            System.out.println("== " + f.getName().replace(".svg", ""));
            for (int y = 0; y < 16; y++) {
                StringBuilder r = new StringBuilder();
                for (int x = 0; x < 16; x++) r.append((img.getRGB(x, y) >>> 24) > 127 ? '#' : '.');
                System.out.println(r);
            }
        }
    }

    // Pixel mode: no antialiasing, strokes rounded to whole device pixels (at least 1).
    static boolean pixel;
    static int cell, pad;
    // Draft: fit the ink's bounds instead of the viewBox, so every icon fills the 12x12 content box.
    static double[] vbOverride;

    private record Style(boolean fill, boolean stroke, double width, float opacity, boolean erase, AffineTransform at, Shape clip) {
    }

    /** Renders one SVG into its cell, fitted with {@link #pad} margin and centred. @return viewBox aspect */
    private static float render(BufferedImage img, File f, int cx, int cy) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element svg = dbf.newDocumentBuilder().parse(f).getDocumentElement();
        double[] vb = vbOverride != null ? vbOverride : nums(svg.getAttribute("viewBox"));
        int inner = cell - 2 * pad;
        double s = inner / Math.max(vb[2], vb[3]);
        AffineTransform at = new AffineTransform();
        at.translate(cx + pad + Math.round((inner - vb[2] * s) / 2), cy + pad + Math.round((inner - vb[3] * s) / 2));
        at.scale(s, s);
        at.translate(-vb[0], -vb[1]);
        Map<String, Element> clips = new HashMap<>();
        for (Node n = svg.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element d && d.getTagName().equals("defs"))
                for (Node k = d.getFirstChild(); k != null; k = k.getNextSibling())
                    if (k instanceof Element cp && cp.getTagName().equals("clipPath")) clips.put(cp.getAttribute("id"), cp);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, pixel ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, pixel ? RenderingHints.VALUE_STROKE_NORMALIZE : RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        g.clip(new java.awt.Rectangle(cx, cy, cell, cell));
        Style root = style(svg, new Style(false, true, 1, 1, false, at, null), clips);
        walk(g, svg, root, clips);
        g.dispose();
        return (float) (vb[2] / vb[3]);
    }

    private static void walk(Graphics2D g, Element parent, Style st, Map<String, Element> clips) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || e.getTagName().equals("defs")) continue;
            Style es = style(e, st, clips);
            if (e.getTagName().equals("g")) walk(g, e, es, clips);
            else draw(g, shape(e), es);
        }
    }

    private static void draw(Graphics2D g, Shape userShape, Style st) {
        if (userShape == null) return;
        Shape dev = st.at.createTransformedShape(userShape);
        double scale = Math.sqrt(Math.abs(st.at.getDeterminant()));
        Shape old = g.getClip();
        if (st.clip != null) g.clip(st.clip);
        if (st.fill) {
            g.setComposite(st.erase ? AlphaComposite.Clear : AlphaComposite.SrcOver.derive(st.opacity));
            g.fill(dev);
        }
        if (st.stroke && st.width > 0) {
            g.setComposite(AlphaComposite.SrcOver.derive(st.opacity));
            float w = (float) (st.width * scale);
            if (pixel) w = Math.max(1, Math.round(w));
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(dev);
        }
        g.setClip(old);
    }

    /** Applies an element's presentation attributes on top of the inherited style. */
    private static Style style(Element e, Style p, Map<String, Element> clips) {
        boolean fill = e.hasAttribute("fill") ? !e.getAttribute("fill").equals("none") : p.fill;
        boolean stroke = e.hasAttribute("stroke") ? !e.getAttribute("stroke").equals("none") : p.stroke;
        double width = e.hasAttribute("stroke-width") ? Double.parseDouble(e.getAttribute("stroke-width")) : p.width;
        float opacity = p.opacity * (e.hasAttribute("opacity") ? Float.parseFloat(e.getAttribute("opacity")) : 1f);
        boolean erase = p.erase || e.getAttribute("data-erase").equals("true");
        AffineTransform at = new AffineTransform(p.at);
        if (e.hasAttribute("transform")) at.concatenate(transform(e.getAttribute("transform")));
        Shape clip = p.clip;
        String ref = e.getAttribute("clip-path");
        if (ref.startsWith("url(#")) {
            Element cp = clips.get(ref.substring(5, ref.length() - 1));
            if (cp == null) throw new IllegalArgumentException("missing clipPath " + ref);
            Area a = new Area();
            for (Node k = cp.getFirstChild(); k != null; k = k.getNextSibling())
                if (k instanceof Element ke) {
                    Shape sh = shape(ke);
                    if (sh == null) continue;
                    a.add(new Area(at.createTransformedShape(sh)));
                    if (ke.hasAttribute("stroke-width"))
                        a.add(new Area(at.createTransformedShape(new BasicStroke(Float.parseFloat(ke.getAttribute("stroke-width")),
                                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(sh))));
                }
            if (clip != null) a.intersect(new Area(clip));
            clip = a;
        }
        return new Style(fill, stroke, width, opacity, erase, at, clip);
    }

    private static Shape shape(Element e) {
        return switch (e.getTagName()) {
            case "path" -> path(e.getAttribute("d"));
            case "line" -> new Line2D.Double(d(e, "x1"), d(e, "y1"), d(e, "x2"), d(e, "y2"));
            case "rect" -> new RoundRectangle2D.Double(d(e, "x"), d(e, "y"), d(e, "width"), d(e, "height"), 2 * d(e, "rx"), 2 * d(e, "rx"));
            case "circle" -> new Ellipse2D.Double(d(e, "cx") - d(e, "r"), d(e, "cy") - d(e, "r"), 2 * d(e, "r"), 2 * d(e, "r"));
            case "polyline" -> {
                double[] p = nums(e.getAttribute("points"));
                Path2D.Double pl = new Path2D.Double();
                pl.moveTo(p[0], p[1]);
                for (int i = 2; i + 1 < p.length; i += 2) pl.lineTo(p[i], p[i + 1]);
                yield pl;
            }
            default -> throw new IllegalArgumentException("unsupported element <" + e.getTagName() + ">");
        };
    }

    private static double d(Element e, String a) {
        return e.hasAttribute(a) ? Double.parseDouble(e.getAttribute(a)) : 0;
    }

    /** Parses an SVG transform list: rotate, translate, scale, matrix. */
    static AffineTransform transform(String s) {
        AffineTransform at = new AffineTransform();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)").matcher(s);
        while (m.find()) {
            double[] a = nums(m.group(2));
            switch (m.group(1)) {
                case "rotate" -> {
                    if (a.length == 3) at.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    else at.rotate(Math.toRadians(a[0]));
                }
                case "translate" -> at.translate(a[0], a.length > 1 ? a[1] : 0);
                case "scale" -> at.scale(a[0], a.length > 1 ? a[1] : a[0]);
                case "matrix" -> at.concatenate(new AffineTransform(a));
                default -> throw new IllegalArgumentException("unsupported transform " + m.group(1));
            }
        }
        return at;
    }

    private static double[] nums(String s) {
        Tok t = new Tok(s);
        List<Double> out = new ArrayList<>();
        while (t.skip()) out.add(t.num());
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Path data tokenizer: numbers in SVG's compact syntax ({@code .5.5}, {@code 1-2}, exponents) and command letters. */
    private static final class Tok {
        final String s;
        int i;

        Tok(String s) {
            this.s = s;
        }

        /** Skips separators. @return true if anything is left */
        boolean skip() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            return i < s.length();
        }

        boolean atCommand() {
            char c = s.charAt(i);
            return Character.isLetter(c) && c != 'e' && c != 'E';
        }

        double num() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) i++;
                else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && i > st) {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                    break;
                } else break;
            }
            if (st == i) throw new IllegalArgumentException("number expected at " + st + " in: " + s);
            return Double.parseDouble(s.substring(st, i));
        }

        /** An arc flag: a single 0 or 1, possibly unseparated from what follows. */
        boolean flag() {
            skip();
            char c = s.charAt(i++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("arc flag expected in: " + s);
            return c == '1';
        }
    }

    /** Parses SVG path data into a Path2D (M L H V C S Q T A Z, absolute and relative). */
    static Path2D.Double path(String d) {
        Path2D.Double p = new Path2D.Double();
        Tok t = new Tok(d);
        double x = 0, y = 0, sx = 0, sy = 0, kx = 0, ky = 0; // current, subpath start, last control point
        char cmd = 0, last = 0;
        while (t.skip()) {
            if (t.atCommand()) cmd = d.charAt(t.i++);
            else if (cmd == 0 || cmd == 'Z' || cmd == 'z') throw new IllegalArgumentException("command expected in: " + d);
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;
            char up = Character.toUpperCase(cmd);
            switch (up) {
                case 'M' -> {
                    x = ox + t.num();
                    y = oy + t.num();
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = rel ? 'l' : 'L'; // further pairs are line-tos
                }
                case 'L' -> {
                    x = ox + t.num();
                    y = oy + t.num();
                    p.lineTo(x, y);
                }
                case 'H' -> {
                    x = ox + t.num();
                    p.lineTo(x, y);
                }
                case 'V' -> {
                    y = oy + t.num();
                    p.lineTo(x, y);
                }
                case 'C', 'S' -> {
                    double x1, y1;
                    if (up == 'C') {
                        x1 = ox + t.num();
                        y1 = oy + t.num();
                    } else {
                        boolean refl = last == 'C' || last == 'S';
                        x1 = refl ? 2 * x - kx : x;
                        y1 = refl ? 2 * y - ky : y;
                    }
                    kx = ox + t.num();
                    ky = oy + t.num();
                    x = ox + t.num();
                    y = oy + t.num();
                    p.curveTo(x1, y1, kx, ky, x, y);
                }
                case 'Q', 'T' -> {
                    if (up == 'Q') {
                        kx = ox + t.num();
                        ky = oy + t.num();
                    } else {
                        boolean refl = last == 'Q' || last == 'T';
                        kx = refl ? 2 * x - kx : x;
                        ky = refl ? 2 * y - ky : y;
                    }
                    x = ox + t.num();
                    y = oy + t.num();
                    p.quadTo(kx, ky, x, y);
                }
                case 'A' -> {
                    double rx = t.num(), ry = t.num(), rot = t.num();
                    boolean large = t.flag(), sweep = t.flag();
                    double nx = ox + t.num(), ny = oy + t.num();
                    arc(p, x, y, rx, ry, rot, large, sweep, nx, ny);
                    x = nx;
                    y = ny;
                }
                case 'Z' -> {
                    p.closePath();
                    x = sx;
                    y = sy;
                }
                default -> throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            last = up == 'M' ? 'L' : up;
        }
        return p;
    }

    /** Endpoint-parameterized elliptical arc (SVG 1.1 F.6.5) as cubic Béziers of at most 90° each. */
    private static void arc(Path2D p, double x0, double y0, double rx, double ry, double rotDeg, boolean large, boolean sweep, double x, double y) {
        if (x0 == x && y0 == y) return;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            p.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx2 = (x0 - x) / 2, dy2 = (y0 - y) / 2;
        double x1p = cos * dx2 + sin * dy2, y1p = -sin * dx2 + cos * dy2;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = coef * rx * y1p / ry, cyp = -coef * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x0 + x) / 2, cy = sin * cxp + cos * cyp + (y0 + y) / 2;
        double t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;
        int n = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9);
        double delta = dt / n, k = 4.0 / 3 * Math.tan(delta / 4);
        for (int i = 0; i < n; i++) {
            double a = t1 + i * delta, b = a + delta;
            double[] pa = ell(cx, cy, rx, ry, cos, sin, a), pb = ell(cx, cy, rx, ry, cos, sin, b);
            double[] da = dEll(rx, ry, cos, sin, a), db = dEll(rx, ry, cos, sin, b);
            double ex = i == n - 1 ? x : pb[0], ey = i == n - 1 ? y : pb[1];
            p.curveTo(pa[0] + k * da[0], pa[1] + k * da[1], pb[0] - k * db[0], pb[1] - k * db[1], ex, ey);
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }

    private static double[] ell(double cx, double cy, double rx, double ry, double cos, double sin, double a) {
        double ex = rx * Math.cos(a), ey = ry * Math.sin(a);
        return new double[]{cx + cos * ex - sin * ey, cy + sin * ex + cos * ey};
    }

    private static double[] dEll(double rx, double ry, double cos, double sin, double a) {
        double ex = -rx * Math.sin(a), ey = ry * Math.cos(a);
        return new double[]{cos * ex - sin * ey, sin * ex + cos * ey};
    }
}
