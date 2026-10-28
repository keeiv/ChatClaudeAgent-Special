// Web projects in the app's private storage, with per-turn checkpoints. Plain Java (no Android types), so the
// desktop test (android/haiku/test/CoreTest.java) runs the same code the phone does.
package com.moriteahouse.cca.haiku.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Projects {
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FILES = 400;
    public static final int MAX_CHECKPOINTS = 20;
    /** Haiku's own data inside a project (the conversation); never listed, previewed or given to the model. */
    public static final String INTERNAL = ".haiku";

    public static final class FileInfo {
        public final String path;
        public final long size;
        FileInfo(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    public static final class ProjectInfo {
        public final String name;
        public final int files;
        public final long updated;
        ProjectInfo(String name, int files, long updated) {
            this.name = name;
            this.files = files;
            this.updated = updated;
        }
    }

    private final Path root;
    private final Path checkpoints;

    public Projects(Path dataDir) throws IOException {
        root = dataDir.resolve("projects");
        checkpoints = dataDir.resolve("checkpoints");
        Files.createDirectories(root);
        Files.createDirectories(checkpoints);
    }

    /** Letters (any script), digits, spaces, '-' and '_'; 1 to 40 characters; not starting with a dot or space. */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 40 || name.startsWith(".") || name.startsWith(" ")
            || name.endsWith(" ")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    public Path project(String name) throws IOException {
        if (!validName(name)) throw new IOException("專案名稱只能用文字、數字、空格、- 與 _，最多 40 字");
        Path p = root.resolve(name).normalize();
        if (!p.getParent().equals(root.normalize())) throw new IOException("專案名稱不正確");
        return p;
    }

    /**
     * A project-relative path that stays inside the project: forward slashes, no empty or dot segments, no drive or
     * absolute form, and never the internal folder. Every file operation goes through here.
     */
    public Path resolve(String name, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 240) throw new IOException("檔案路徑不正確");
        if (relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
            || relative.indexOf('\0') >= 0) throw new IOException("只能使用專案內的相對路徑");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("只能使用專案內的相對路徑");
        }
        if (relative.equals(INTERNAL) || relative.startsWith(INTERNAL + "/")) throw new IOException("這是 Haiku 的內部資料夾");
        Path base = project(name);
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IOException("只能使用專案內的相對路徑");
        // A link could point outside the project; the app never creates one, so any found is refused.
        for (Path p = target; p != null && p.startsWith(base) && !p.equals(base); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("不支援連結");
        }
        return target;
    }

    public List<ProjectInfo> list() throws IOException {
        List<ProjectInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = dir.getFileName().toString();
                if (!validName(name)) continue;
                List<FileInfo> files = files(name);
                long updated = Files.getLastModifiedTime(dir).toMillis();
                for (FileInfo f : files) {
                    updated = Math.max(updated, Files.getLastModifiedTime(dir.resolve(f.path)).toMillis());
                }
                out.add(new ProjectInfo(name, files.size(), updated));
            }
        }
        out.sort(Comparator.comparingLong((ProjectInfo p) -> -p.updated));
        return out;
    }

    /** A new project with a small page, its stylesheet and script, so the preview shows something at once. */
    public void create(String name) throws IOException {
        Path p = project(name);
        if (Files.exists(p)) throw new IOException("已經有同名的專案");
        Files.createDirectories(p);
        write(name, "index.html", "<!doctype html>\n<html lang=\"zh-Hant-TW\">\n<head>\n  <meta charset=\"utf-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>" + name
            + "</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <main>\n    <h1>" + name
            + "</h1>\n    <p id=\"status\">從對話告訴 Haiku 你想做什麼。</p>\n  </main>\n  <script src=\"app.js\"></script>\n"
            + "</body>\n</html>\n");
        write(name, "style.css", "body { margin: 0; font-family: system-ui, sans-serif; line-height: 1.6; }\n"
            + "main { max-width: 640px; margin: 0 auto; padding: 24px 20px; }\n");
        write(name, "app.js", "// 頁面載入後執行。\ndocument.getElementById('status').dataset.ready = 'true';\n");
    }

    public void delete(String name) throws IOException {
        Path p = project(name);
        if (!Files.isDirectory(p)) throw new IOException("找不到專案");
        deleteTree(p);
        deleteTree(checkpointsOf(name));
    }

    public List<FileInfo> files(String name) throws IOException {
        Path base = project(name);
        List<FileInfo> out = new ArrayList<>();
        if (!Files.isDirectory(base)) throw new IOException("找不到專案");
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && (dir.getFileName().toString().equals(INTERNAL) || attrs.isSymbolicLink()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) out.add(new FileInfo(relative(base, file), attrs.size()));
                return out.size() > MAX_FILES * 2 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing((FileInfo f) -> f.path));
        return out;
    }

    public String read(String name, String relative) throws IOException {
        Path f = resolve(name, relative);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException("找不到檔案 " + relative);
        if (Files.size(f) > MAX_FILE_BYTES) throw new IOException("檔案超過 2 MiB");
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    public byte[] readBytes(String name, String relative) throws IOException {
        Path f = resolve(name, relative);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException("找不到檔案 " + relative);
        if (Files.size(f) > MAX_FILE_BYTES) throw new IOException("檔案超過 2 MiB");
        return Files.readAllBytes(f);
    }

    public void write(String name, String relative, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FILE_BYTES) throw new IOException("檔案超過 2 MiB");
        Path f = resolve(name, relative);
        if (!Files.exists(f) && files(name).size() >= MAX_FILES) throw new IOException("每個專案最多 " + MAX_FILES + " 個檔案");
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".haiku-tmp");
        Files.write(tmp, data);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void remove(String name, String relative) throws IOException {
        Path f = resolve(name, relative);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException("找不到檔案 " + relative);
        Files.delete(f);
    }

    /** Haiku's own files (the conversation) in the project's internal folder. */
    public String readInternal(String name, String file) throws IOException {
        Path f = internal(name, file);
        return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "";
    }

    public void writeInternal(String name, String file, String text) throws IOException {
        Path f = internal(name, file);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(file + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path internal(String name, String file) throws IOException {
        if (!file.matches("[a-z]{1,20}\\.json")) throw new IOException("內部檔名不正確");
        return project(name).resolve(INTERNAL).resolve(file);
    }

    /** Copies the project (without the internal folder) before a turn, keeping the last MAX_CHECKPOINTS. */
    public String checkpoint(String name) throws IOException {
        Path base = project(name);
        String id = String.valueOf(System.currentTimeMillis());
        Path dir = checkpointsOf(name).resolve(id);
        while (Files.exists(dir)) {
            id = String.valueOf(Long.parseLong(id) + 1);
            dir = checkpointsOf(name).resolve(id);
        }
        Files.createDirectories(dir);
        for (FileInfo f : files(name)) {
            Path target = dir.resolve(f.path);
            Files.createDirectories(target.getParent());
            Files.copy(base.resolve(f.path), target);
        }
        List<String> all = checkpoints(name);
        for (int i = 0; i < all.size() - MAX_CHECKPOINTS; i++) deleteTree(checkpointsOf(name).resolve(all.get(i)));
        return id;
    }

    public List<String> checkpoints(String name) throws IOException {
        List<String> ids = new ArrayList<>();
        Path dir = checkpointsOf(name);
        if (!Files.isDirectory(dir)) return ids;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                String id = p.getFileName().toString();
                if (id.matches("[0-9]{1,19}")) ids.add(id);
            }
        }
        ids.sort(Comparator.comparingLong(Long::parseLong));
        return ids;
    }

    /** Puts the project back exactly as the checkpoint saw it; the conversation in the internal folder stays. */
    public void restore(String name, String id) throws IOException {
        if (id == null || !id.matches("[0-9]{1,19}")) throw new IOException("找不到這個還原點");
        Path saved = checkpointsOf(name).resolve(id);
        if (!Files.isDirectory(saved)) throw new IOException("找不到這個還原點");
        Path base = project(name);
        for (FileInfo f : files(name)) Files.delete(base.resolve(f.path));
        Files.walkFileTree(saved, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = base.resolve(relative(saved, file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        removeEmptyFolders(base);
    }

    public void exportZip(String name, OutputStream out) throws IOException {
        Path base = project(name);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (FileInfo f : files(name)) {
                zip.putNextEntry(new ZipEntry(name + "/" + f.path));
                Files.copy(base.resolve(f.path), zip);
                zip.closeEntry();
            }
        }
    }

    private Path checkpointsOf(String name) throws IOException {
        project(name);
        // Folder names stay ASCII so any project name maps to a safe directory.
        StringBuilder hex = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) hex.append(String.format("%02x", b));
        return checkpoints.resolve(hex.toString());
    }

    private static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static void removeEmptyFolders(Path base) throws IOException {
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (!dir.equals(base) && !dir.getFileName().toString().equals(INTERNAL)) {
                    try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
                        if (!s.iterator().hasNext()) Files.delete(dir);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
