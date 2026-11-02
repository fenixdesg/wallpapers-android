import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arma el sitio que lee la app a partir de la carpeta packs/.
 *
 * Uso: java tools/BuildCatalog.java packs site
 *
 * Cada subcarpeta de packs/ es un fondo con sus capas 1.png, 2.png ... 6.png (de atrás hacia
 * adelante; también sirve .jpg). Opcional: info.json con {"name": "Nombre visible"}.
 * Genera site/catalog.json (lista con huellas SHA-256), una miniatura por fondo y las capas
 * optimizadas. Los archivos llevan su huella en el nombre, así nunca se sirve una copia vieja.
 * La firma (site/catalog.sig) la agrega después el workflow con la clave privada.
 */
public class BuildCatalog {

    static final int MAX_LAYERS = 6;
    static final int MAX_SOURCE_SIDE = 8192;
    static final int STORED_MAX_SIDE = 3000;
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final int PREVIEW_W = 540, PREVIEW_H = 1170;
    /** Igual que ParallaxRenderer.OVERSCAN en la app, para que la miniatura coincida. */
    static final float OVERSCAN = 0.15f;
    static final Pattern LAYER = Pattern.compile("^([1-6])\\.(png|jpe?g)$", Pattern.CASE_INSENSITIVE);

    static int warnings = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path src = Path.of(args.length > 0 ? args[0] : "packs");
        Path out = Path.of(args.length > 1 ? args[1] : "site");
        if (!Files.isDirectory(src)) fail("No existe la carpeta " + src);
        deleteTree(out);
        Files.createDirectories(out);
        boolean webp = hasCwebp();
        System.out.println(webp ? "Capas: WebP (cwebp)" : "Capas: PNG/JPG originales (no hay cwebp)");

        File[] dirs = src.toFile().listFiles(File::isDirectory);
        if (dirs == null) dirs = new File[0];
        Arrays.sort(dirs);
        List<String> entries = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (File dir : dirs) {
            if (dir.getName().startsWith(".")) continue;
            String id = slug(dir.getName());
            if (id.isEmpty()) { warn(dir.getName(), "el nombre de la carpeta no sirve como identificador"); continue; }
            if (ids.contains(id)) { warn(dir.getName(), "hay otra carpeta con el mismo nombre (" + id + ")"); continue; }
            try {
                String entry = buildPack(dir, id, out, webp);
                if (entry != null) { entries.add(entry); ids.add(id); }
            } catch (Exception e) {
                warn(dir.getName(), "no se pudo procesar: " + e.getMessage());
            }
        }

        long generated = System.currentTimeMillis() / 1000;
        StringBuilder json = new StringBuilder();
        json.append("{\"format\":1,\"generated\":").append(generated).append(",\"packs\":[\n");
        json.append(String.join(",\n", entries));
        json.append("\n]}\n");
        Files.writeString(out.resolve("catalog.json"), json.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("index.html"),
            "<!doctype html><meta charset=utf-8><title>Parallax 3D</title><p>Parallax 3D: " + entries.size() + " fondos.</p>\n");
        Files.writeString(out.resolve(".nojekyll"), "");
        System.out.println("Listo: " + entries.size() + " fondos" + (warnings > 0 ? ", " + warnings + " avisos" : ""));
    }

    static String buildPack(File dir, String id, Path out, boolean webp) throws Exception {
        File[] layers = new File[MAX_LAYERS];
        File[] files = dir.listFiles(File::isFile);
        if (files == null) files = new File[0];
        for (File f : files) {
            Matcher m = LAYER.matcher(f.getName());
            if (!m.matches()) continue;
            int i = Integer.parseInt(m.group(1)) - 1;
            if (layers[i] != null) { warn(dir.getName(), "hay dos archivos para la capa " + (i + 1)); return null; }
            layers[i] = f;
        }
        int count = 0;
        while (count < MAX_LAYERS && layers[count] != null) count++;
        if (count == 0) { warn(dir.getName(), "no tiene capas (1.png, 2.png...)"); return null; }
        for (int i = count; i < MAX_LAYERS; i++) {
            if (layers[i] != null) { warn(dir.getName(), "falta la capa " + (count + 1)); return null; }
        }

        Path packOut = out.resolve("p").resolve(id);
        Files.createDirectories(packOut);
        BufferedImage[] images = new BufferedImage[count];
        StringBuilder fileList = new StringBuilder();
        for (int i = 0; i < count; i++) {
            File f = layers[i];
            if (f.length() > MAX_FILE_BYTES) { warn(dir.getName(), f.getName() + " pesa más de 25 MB"); return null; }
            BufferedImage img = ImageIO.read(f);
            if (img == null) { warn(dir.getName(), f.getName() + " no es una imagen PNG o JPG válida"); return null; }
            if (img.getWidth() > MAX_SOURCE_SIDE || img.getHeight() > MAX_SOURCE_SIDE) {
                warn(dir.getName(), f.getName() + " es demasiado grande (máx. 8192 px)"); return null;
            }
            boolean resized = Math.max(img.getWidth(), img.getHeight()) > STORED_MAX_SIDE;
            if (resized) img = fit(img, STORED_MAX_SIDE);
            images[i] = img;

            String ext = f.getName().substring(f.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            Path tmp = packOut.resolve("tmp-" + i + "." + (resized ? "png" : ext));
            if (resized) ImageIO.write(img, "png", tmp.toFile());
            else Files.copy(f.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Path finalTmp = tmp;
            if (webp) {
                Path w = packOut.resolve("tmp-" + i + ".webp");
                if (cwebp(tmp, w)) { Files.delete(tmp); finalTmp = w; ext = "webp"; } else Files.deleteIfExists(w);
            } else if (resized) ext = "png";
            fileList.append(i == 0 ? "" : ",").append(publish(finalTmp, out, id, ext));
        }

        BufferedImage preview = composePreview(images);
        Path pTmp = packOut.resolve("tmp-preview.jpg");
        ImageIO.write(preview, "jpg", pTmp.toFile());
        String previewEntry = publish(pTmp, out, id, "jpg");

        String name = readName(dir);
        long added = firstCommitTime(dir);
        return "{\"id\":\"" + id + "\",\"name\":" + jsonString(name) + ",\"layers\":" + count
            + ",\"added\":" + added + ",\"preview\":" + previewEntry + ",\"files\":[" + fileList + "]}";
    }

    /** Renombra el archivo con su huella y devuelve su entrada {path, sha256, size}. */
    static String publish(Path file, Path out, String id, String ext) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String rel = "p/" + id + "/" + sha.substring(0, 16) + "." + ext;
        Files.move(file, out.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
        return "{\"path\":\"" + rel + "\",\"sha256\":\"" + sha + "\",\"size\":" + bytes.length + "}";
    }

    /** Todas las capas juntas en reposo, igual que las dibuja la app. */
    static BufferedImage composePreview(BufferedImage[] layers) {
        BufferedImage out = new BufferedImage(PREVIEW_W, PREVIEW_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, PREVIEW_W, PREVIEW_H);
        g.setComposite(AlphaComposite.SrcOver);
        for (BufferedImage b : layers) {
            float cover = Math.max(PREVIEW_W / (float) b.getWidth(), PREVIEW_H / (float) b.getHeight());
            float scale = cover * (1f + OVERSCAN);
            int dw = Math.round(b.getWidth() * scale), dh = Math.round(b.getHeight() * scale);
            g.drawImage(b, (PREVIEW_W - dw) / 2, (PREVIEW_H - dh) / 2, dw, dh, null);
        }
        g.dispose();
        return out;
    }

    static BufferedImage fit(BufferedImage img, int maxSide) {
        float s = maxSide / (float) Math.max(img.getWidth(), img.getHeight());
        int w = Math.max(1, Math.round(img.getWidth() * s)), h = Math.max(1, Math.round(img.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static String readName(File dir) {
        File info = new File(dir, "info.json");
        if (info.isFile()) {
            try {
                Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(Files.readString(info.toPath(), StandardCharsets.UTF_8));
                if (m.find()) {
                    String n = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").strip();
                    if (!n.isEmpty()) return limit(n);
                }
            } catch (IOException ignored) { }
            warn(dir.getName(), "info.json no tiene un \"name\" válido; se usa el nombre de la carpeta");
        }
        String n = dir.getName().replace('-', ' ').replace('_', ' ').strip();
        return limit(n.isEmpty() ? dir.getName() : Character.toUpperCase(n.charAt(0)) + n.substring(1));
    }

    static String limit(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(c -> !Character.isISOControl(c)).limit(40).forEach(b::appendCodePoint);
        return b.toString().strip();
    }

    /** "Montaña Azul" -> "montana-azul" */
    static String slug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return s.length() > 40 ? s.substring(0, 40).replaceAll("-$", "") : s;
    }

    /** Fecha en que la carpeta entró al repositorio (para ordenar los nuevos primero). */
    static long firstCommitTime(File dir) {
        try {
            Process p = new ProcessBuilder("git", "log", "--diff-filter=A", "--format=%ct", "--", dir.getPath())
                .redirectErrorStream(true).start();
            String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && !outText.isEmpty()) {
                String[] lines = outText.split("\\s+");
                return Long.parseLong(lines[lines.length - 1]);
            }
        } catch (Exception ignored) { }
        return System.currentTimeMillis() / 1000;
    }

    static boolean hasCwebp() {
        try {
            Process p = new ProcessBuilder("cwebp", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    /** Con pérdida suave en el color y transparencia exacta: mucho más liviano que PNG. */
    static boolean cwebp(Path in, Path out) {
        try {
            Process p = new ProcessBuilder("cwebp", "-quiet", "-q", "90", "-alpha_q", "100", "-exact", "-m", "5",
                in.toString(), "-o", out.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.size(out) > 0;
        } catch (Exception e) { return false; }
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    static void warn(String pack, String msg) {
        warnings++;
        // Formato de anotación de GitHub Actions: aparece como aviso amarillo en la ejecución.
        System.out.println("::warning title=" + pack + "::" + pack + ": " + msg);
    }

    static void fail(String msg) {
        System.out.println("::error::" + msg);
        System.exit(1);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> x.toFile().delete());
        }
    }
}
