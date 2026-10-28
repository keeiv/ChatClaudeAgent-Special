// Desktop test of CCA Haiku's plain-Java core: project paths, files, checkpoints, export, and the HTTPS client
// against a local server (the key must travel as a header only and never come back in a reply).
import com.moriteahouse.cca.haiku.core.ModelClient;
import com.moriteahouse.cca.haiku.core.Projects;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

public final class CoreTest {
    private static int checks, failures;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }

    private interface Body { void run() throws Exception; }

    private static boolean refuses(Body body) {
        try {
            body.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("haiku-core");
        Projects p = new Projects(data);
        check(Projects.validName("記帳小工具") && Projects.validName("my app_2") && !Projects.validName("../x") &&
                  !Projects.validName(".hidden") && !Projects.validName("a/b") && !Projects.validName(""),
              "project names allow any script but no paths");
        p.create("記帳小工具");
        check(p.files("記帳小工具").size() == 3 && p.read("記帳小工具", "index.html").contains("<title>記帳小工具</title>"),
              "a new project starts with a page, a stylesheet and a script");
        check(refuses(() -> p.create("記帳小工具")), "a second project with the same name is refused");
        for (String bad : new String[] {"../escape.txt", "/etc/passwd", "C:/x.txt", "a\\b.txt", "a//b.txt", "./a.txt",
                                         ".haiku/chat.json", ".haiku", "x/../../y"}) {
            check(refuses(() -> p.write("記帳小工具", bad, "x")), "path refused: " + bad);
        }
        p.write("記帳小工具", "js/chart.js", "const x = 1;\n");
        check(p.read("記帳小工具", "js/chart.js").equals("const x = 1;\n"), "nested files are written and read back");
        check(!Files.exists(data.resolve("escape.txt")) && !Files.exists(data.resolve("projects").resolve("escape.txt")),
              "nothing was written outside the project");
        p.writeInternal("記帳小工具", "chat.json", "{\"turns\":[]}");
        boolean hidden = true;
        for (Projects.FileInfo f : p.files("記帳小工具")) hidden &= !f.path.startsWith(".haiku");
        check(hidden && p.readInternal("記帳小工具", "chat.json").equals("{\"turns\":[]}"),
              "the conversation is stored but never listed as a project file");
        check(refuses(() -> p.write("記帳小工具", "big.txt", new String(new char[Projects.MAX_FILE_BYTES + 1]).replace('\0', 'a'))),
              "files over 2 MiB are refused");

        String before = p.checkpoint("記帳小工具");
        p.write("記帳小工具", "index.html", "<p>changed</p>");
        p.write("記帳小工具", "extra/new.html", "<p>new</p>");
        p.remove("記帳小工具", "style.css");
        p.restore("記帳小工具", before);
        check(p.read("記帳小工具", "index.html").contains("<title>記帳小工具</title>") &&
                  refuses(() -> p.read("記帳小工具", "extra/new.html")) && p.read("記帳小工具", "style.css").contains("body"),
              "restoring a checkpoint brings back changed, added and deleted files");
        check(!Files.exists(data.resolve("projects").resolve("記帳小工具").resolve("extra")), "empty folders left by a restore are removed");
        check(p.readInternal("記帳小工具", "chat.json").equals("{\"turns\":[]}"), "restoring files keeps the conversation");
        for (int i = 0; i < Projects.MAX_CHECKPOINTS + 3; i++) p.checkpoint("記帳小工具");
        check(p.checkpoints("記帳小工具").size() == Projects.MAX_CHECKPOINTS, "only the most recent checkpoints are kept");

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        p.exportZip("記帳小工具", zip);
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) {
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null;) names.add(e.getName());
        }
        check(names.contains("記帳小工具/index.html") && names.contains("記帳小工具/js/chart.js") &&
                  names.stream().noneMatch(n -> n.contains(".haiku")),
              "the exported ZIP holds the project files without Haiku's own data");
        p.delete("記帳小工具");
        check(p.list().isEmpty(), "a deleted project is gone");

        // The HTTPS client against a local server: headers carry the key, the body passes through unchanged.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> seen = new AtomicReference<>("");
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " | " +
                     exchange.getRequestHeaders().getFirst("x-api-key") + " | " +
                     exchange.getRequestHeaders().getFirst("anthropic-version") + " | " +
                     exchange.getRequestHeaders().getFirst("Authorization") + " | " + body);
            boolean bad = exchange.getRequestURI().getPath().endsWith("/completions");
            byte[] reply = (bad ? "{\"error\":{\"message\":\"invalid key sk-test-openai-123456\"}}" : "{\"ok\":true}")
                               .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(bad ? 401 : 200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ModelClient client = new ModelClient(base + "/anthropic", base + "/openai");
        ModelClient.Response r = client.send("anthropic", "sk-test-anthropic-123456", "{\"model\":\"m\"}");
        check(r.status == 200 && seen.get().equals("POST /anthropic/messages | sk-test-anthropic-123456 | 2023-06-01 | null | {\"model\":\"m\"}"),
              "Anthropic requests carry the key and version headers and the body unchanged");
        ModelClient.Response models = client.models("openai", "sk-test-openai-123456");
        check(models.status == 200 && seen.get().startsWith("GET /openai/models | null | null | Bearer sk-test-openai-123456"),
              "OpenAI model listing uses a bearer token");
        ModelClient.Response error = client.send("openai", "sk-test-openai-123456", "{}");
        check(error.status == 401 && !error.body.contains("sk-test-openai-123456") && error.body.contains("[已隱藏]"),
              "a provider error that echoes the key is redacted before it reaches the page");
        check(refuses(() -> client.send("gemini", "k", "{}")) && refuses(() -> client.send("anthropic", "", "{}")),
              "unknown providers and missing keys are refused before any request");
        server.stop(0);
        System.out.println(checks + " Haiku core checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
