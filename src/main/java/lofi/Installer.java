package lofi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registers the wallpaper with Wallpaper Engine, on whatever machine it is run.
 *
 * Why this exists: opening a wallpaper by file path is not the same as having it
 * installed. Wallpaper Engine only scans projects/myprojects at startup, and only
 * what it finds there becomes a library entry it will restore. A wallpaper opened
 * by path shows up as the bare filename with a blank icon - and, more to the
 * point, is not put back when the display goes away and returns, which is what a
 * machine going to sleep looks like from the engine's side. The desktop then
 * falls back to the Windows wallpaper.
 *
 * Installing means one directory under myprojects. By default the wallpaper's
 * files are copied into it, because that is what someone who downloaded this
 * needs: install once, delete the download, keep the wallpaper. A junction would
 * tie the installed wallpaper to a folder that is about to disappear.
 *
 * Only the wallpaper's own files are copied - index.html, project.json, css, js.
 * The server, the git history and the CI config are not part of the wallpaper and
 * Wallpaper Engine scans everything it is given, so handing it the whole tree
 * would be both slow and wrong.
 *
 * --link installs a junction instead, for working on the wallpaper: the installed
 * copy *is* the working tree, so an edit shows up with nothing to re-sync.
 * Junctions need no administrator rights, unlike symbolic links.
 *
 * Run:  java -jar lofi-server.jar --install
 *       java -jar lofi-server.jar --install --link
 *       java -jar lofi-server.jar --uninstall
 */
final class Installer {

    private static final String PROJECT_DIR_NAME = "lofi-wallpaper";

    private Installer() {}

    /** Only these are the wallpaper; everything else in the repo is not. */
    private static final List<String> CONTENT = List.of(
            "index.html", "project.json", "css", "js");

    static int run(boolean uninstall, boolean link0) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.err.println("Wallpaper Engine's project folder layout here is Windows-only.");
            return 1;
        }

        Path engine = findEngine();
        if (engine == null) {
            System.err.println("""
                    Could not find Wallpaper Engine.
                    Looked through every Steam library listed in libraryfolders.vdf.
                    Pass the folder yourself:
                      java -jar lofi-server.jar --install "D:\\...\\common\\wallpaper_engine\"""");
            return 1;
        }

        Path projects = engine.resolve("projects").resolve("myprojects");
        Path link = projects.resolve(PROJECT_DIR_NAME);

        if (uninstall) {
            return remove(link);
        }
        final boolean linkMode = link0;

        Path wallpaper = findWallpaperRoot();
        if (wallpaper == null) {
            System.err.println("Could not find the wallpaper folder (the one holding project.json "
                    + "and index.html) above this jar.");
            return 1;
        }

        System.out.println("  engine    : " + engine);
        System.out.println("  wallpaper : " + wallpaper);

        try {
            Files.createDirectories(projects);
        } catch (IOException e) {
            System.err.println("  cannot create " + projects + ": " + e.getMessage());
            return 1;
        }

        if (Files.exists(link)) {
            // A junction already aimed at this same tree is the finished state.
            // A copy is always redone: the source may have changed since, and
            // there is no cheap way to tell from here.
            if (linkMode && isLink(link) && realPathOf(link) != null
                    && realPathOf(link).equals(realPathOf(wallpaper))) {
                System.out.println("  already installed - nothing to do");
                return 0;
            }
            System.out.println("  replacing what was there");
            if (remove(link) != 0) return 1;
        }

        if (linkMode) {
            int code = exec("cmd", "/c", "mklink", "/J", link.toString(), wallpaper.toString());
            if (code != 0) {
                System.err.println("  mklink failed (exit " + code + ")");
                return 1;
            }
            System.out.println("  installed : " + link + "  (linked to the working tree)");
        } else {
            try {
                long bytes = copyContent(wallpaper, link);
                System.out.println("  installed : " + link + "  (" + (bytes / 1024) + " KiB copied)");
                System.out.println("  This folder is now self-contained - the download can be deleted.");
            } catch (IOException e) {
                System.err.println("  copy failed: " + e.getMessage());
                return 1;
            }
        }
        System.out.println();
        System.out.println("  Restart Wallpaper Engine - it only reads this folder at startup -");
        System.out.println("  then pick \"Lofi Girl Wallpaper Radio\" from Installed rather than");
        System.out.println("  opening index.html by path. Only a library entry gets restored when");
        System.out.println("  the display comes back after sleep.");
        return 0;
    }

    private static int remove(Path link) {
        if (!Files.exists(link)) {
            System.out.println("  not installed - nothing to remove");
            return 0;
        }

        // The two cases must not be confused. Deleting a junction's contents would
        // delete the working tree behind it; rmdir on its own removes only the
        // junction. A copied folder is the opposite and has to go recursively.
        if (isLink(link)) {
            int code = exec("cmd", "/c", "rmdir", link.toString());
            if (code != 0) {
                System.err.println("  could not remove the link " + link);
                return 1;
            }
        } else {
            // Guard against ever recursing over something that is not ours.
            if (!link.getFileName().toString().equals(PROJECT_DIR_NAME)
                    || !link.getParent().getFileName().toString().equals("myprojects")) {
                System.err.println("  refusing to delete " + link);
                return 1;
            }
            try (var walk = Files.walk(link)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            } catch (IOException e) {
                System.err.println("  could not remove " + link + ": " + e.getMessage());
                return 1;
            }
        }
        System.out.println("  removed   : " + link);
        return 0;
    }

    private static boolean isLink(Path p) {
        // A junction is a reparse point, which shows up as "other" rather than as
        // a directory or a regular file.
        try {
            return Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies just the wallpaper's own files, returning how many bytes landed. */
    private static long copyContent(Path from, Path to) throws IOException {
        long total = 0;
        Files.createDirectories(to);
        for (String name : CONTENT) {
            Path src = from.resolve(name);
            if (!Files.exists(src)) continue;
            if (Files.isDirectory(src)) {
                try (var walk = Files.walk(src)) {
                    for (Path p : walk.toList()) {
                        Path dst = to.resolve(from.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(dst);
                        } else {
                            Files.createDirectories(dst.getParent());
                            Files.copy(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            total += Files.size(p);
                        }
                    }
                }
            } else {
                Files.copy(src, to.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                total += Files.size(src);
            }
        }
        return total;
    }

    /** The wallpaper is the nearest folder above this jar holding project.json. */
    private static Path findWallpaperRoot() {
        try {
            Path here = Path.of(Installer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            for (Path p = here; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve("project.json"))
                        && Files.isRegularFile(p.resolve("index.html"))) {
                    return p.toRealPath();
                }
            }
        } catch (Exception e) {
            // falls through to the working directory below
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("project.json"))
                    && Files.isRegularFile(p.resolve("index.html"))) {
                try { return p.toRealPath(); } catch (IOException e) { return p; }
            }
        }
        return null;
    }

    /**
     * Finds Wallpaper Engine through Steam rather than guessing drive letters.
     *
     * Steam records its own location in the registry, and every extra library
     * folder in libraryfolders.vdf - which is how a machine with games on D: is
     * found without hard-coding D:.
     */
    private static Path findEngine() {
        Set<Path> libraries = new LinkedHashSet<>();

        Path steam = steamFromRegistry();
        if (steam != null) libraries.add(steam);
        libraries.addAll(List.of(
                Path.of("C:", "Program Files (x86)", "Steam"),
                Path.of("C:", "Program Files", "Steam")));

        for (Path s : new ArrayList<>(libraries)) {
            libraries.addAll(librariesFromVdf(s.resolve("steamapps").resolve("libraryfolders.vdf")));
        }

        for (Path lib : libraries) {
            Path engine = lib.resolve("steamapps").resolve("common").resolve("wallpaper_engine");
            if (Files.isDirectory(engine.resolve("projects"))) return engine;
        }
        return null;
    }

    private static Path steamFromRegistry() {
        // Two hives: the per-user one is set by the installer, the machine one by
        // the 32-bit Steam package. Either can be the only one present.
        for (String key : new String[]{
                "HKCU\\Software\\Valve\\Steam /v SteamPath",
                "HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam /v InstallPath"}) {
            String out = capture(("reg query " + key).split(" "));
            Matcher m = Pattern.compile("REG_SZ\\s+(.+)").matcher(out == null ? "" : out);
            if (m.find()) {
                Path p = Path.of(m.group(1).trim());
                if (Files.isDirectory(p)) return p;
            }
        }
        return null;
    }

    private static Set<Path> librariesFromVdf(Path vdf) {
        Set<Path> found = new LinkedHashSet<>();
        if (!Files.isRegularFile(vdf)) return found;
        try {
            String text = Files.readString(vdf, StandardCharsets.UTF_8);
            // "path"   "D:\\SteamLibrary"
            Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(text);
            while (m.find()) {
                Path p = Path.of(m.group(1).replace("\\\\", "\\"));
                if (Files.isDirectory(p)) found.add(p);
            }
        } catch (IOException e) {
            // an unreadable vdf just means fewer candidates
        }
        return found;
    }

    private static Path realPathOf(Path p) {
        try { return p.toRealPath(); } catch (IOException e) { return null; }
    }

    private static String capture(String... cmd) {
        try {
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                proc.waitFor();
                return sb.toString();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static int exec(String... cmd) {
        try {
            Process proc = new ProcessBuilder(cmd).inheritIO().start();
            return proc.waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return 1;
        }
    }
}
