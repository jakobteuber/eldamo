package eldamo;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import net.sf.saxon.s9api.XdmNodeKind;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static net.sf.saxon.s9api.Serializer.Property.*;

/// Development server: `/content/…` pages are rendered from the data, everything else is served from [#WEBAPP].
/// Under `/pub/…` the same pages are rendered for publication.
class Main {

    static final Path WEBAPP = Path.of("src/main/webapp");

    /// `{name}` matches a query parameter, `*` any file extension
    private static final Map<Pattern, String> PAGES = pages(
        "/data-model/eldamo-data.xml", "data-model.xsl",
        "/test/test.*", "test.xq",
        "/csv/word-export.*", "word-export.xq",
        "/csv/base-export.*", "base-export.xq",
        "/valid/note-check.*", "valid-note-check.xq",
        "/etym/etym.*", "etym.xq",
        "/sort.*", "sort.xsl",
        "/sort-merge.*", "sort-merge.xsl",
        "/compare-refs.*", "compare-refs.xsl",
        "/languages/index.*", "language-index.xq",
        "/language-pages/lang-{id}.*", "language-page.xq",
        "/word-indexes/words-{id}.*", "word-index.xq",
        "/vocabulary-indexes/vocabulary-words-{id}.*", "vocabulary-index.xq",
        "/neologism-indexes/neologisms-{id}.*", "neologism-index.xq",
        "/deprecation-indexes/deprecations-{id}.*", "deprecation-index.xq",
        "/words/word-{code}.*", "word-page.xq",
        "/category-indexes/categories-{id}.*", "categories-index.xq",
        "/root-indexes/roots-{id}.*", "root-index.xq",
        "/name-indexes/names-{id}.*", "name-index.xq",
        "/phrase-indexes/phrases-{id}.*", "phrase-index.xq",
        "/grammar-indexes/grammars-{id}.*", "grammar-index.xq",
        "/phonetic-indexes/phonetics-{id}.*", "phonetic-index.xq",
        "/references/index.*", "reference-index.xq",
        "/references/ref-{prefix}.*", "reference-page.xq",
        "/errors/unmatched-links.*", "unmatched-links.xq",
        "/errors/misordered-rules.*", "misordered-rules.xq",
        "/search/search-index.js", "search-index.xq",
        "/search/search.html", "search.xq",
        "/search/search-adv-index.js", "search-adv-index.xq",
        "/search/advanced-search.html", "search-adv.xq",
        "/sindarin-dictionary/export.*", "sindarin-dictionary.xq",
        "/site-pages.txt", "site-pages.xq"
    );

    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html", "xml", "text/xml", "txt", "text/plain", "js", "application/javascript");

    static void main(String... args) throws Exception {
        if (List.of(args).equals(List.of("release"))) {
            release(Path.of("build/site"));
            return;
        }
        Data.get();
        Javalin.create(cfg -> {
            cfg.router.contextPath = "/eldamo";
            for (var pub : List.of(false, true)) {
                var prefix = pub ? "/pub" : "";
                cfg.staticFiles.add(s -> {
                    s.hostedPath = prefix.isEmpty() ? "/" : prefix;
                    s.directory = WEBAPP.toString();
                    s.location = Location.EXTERNAL;
                });
                cfg.routes.get(prefix + "/", ctx -> ctx.html(home(pub)));
                cfg.routes.get(prefix + "/index.html", ctx -> ctx.html(home(pub)));
                cfg.routes.get(prefix + "/content/<path>", ctx -> content(ctx, pub));
            }
            cfg.routes.get("/merge", ctx -> {
                replaceData("sort-merge.xsl");
                ctx.redirect("index.html");
            });
            cfg.routes.get("/accept-refs", ctx -> {
                replaceData("sort.xsl");
                Files.copy(Data.FILE, Data.OLD, REPLACE_EXISTING);
                ctx.redirect("index.html");
            });
            cfg.routes.exception(Exception.class, (e, ctx) -> {
                e.printStackTrace();
                ctx.status(500).contentType("text/plain").result(e.toString());
            });
        }).start(8080);
        IO.println("Serving on http://localhost:8080/eldamo/");
    }

    private static Map<Pattern, String> pages(String... entries) {
        var pages = new LinkedHashMap<Pattern, String>();
        for (var i = 0; i < entries.length; i += 2) {
            var regex = Pattern.quote(entries[i])
                .replace("*", "\\E[^/]+\\Q")
                .replaceAll("\\{(\\w+)}", "\\\\E(?<$1>[^/]*?)\\\\Q");
            pages.put(Pattern.compile(regex), entries[i + 1]);
        }
        return pages;
    }

    private static void content(Context ctx, boolean pub) throws Exception {
        var path = "/" + ctx.pathParam("path");
        var query = new HashMap<String, String>();
        ctx.queryParamMap().forEach((k, v) -> query.put(k, v.getFirst()));
        var page = render(path, pub, query);
        if (page == null) ctx.status(404).result("No page for " + path);
        else ctx.contentType(TYPES.get(path.substring(path.lastIndexOf('.') + 1)) + "; charset=UTF-8").result(page);
    }

    /// Renders a page under `/content`, or returns null if there is none
    private static String render(String path, boolean pub, Map<String, String> query) throws Exception {
        var ext = path.substring(path.lastIndexOf('.') + 1);
        if (!TYPES.containsKey(ext)) return null;
        for (var page : PAGES.entrySet()) {
            var m = page.getKey().matcher(path);
            if (!m.matches()) continue;
            var params = new HashMap<String, String>();
            m.namedGroups().keySet().forEach(g -> params.put(g, m.group(g)));
            params.putAll(query);
            params.put("pubmode", String.valueOf(pub));
            params.put("old-data", Data.OLD.toUri().toString());
            var out = new StringWriter();
            var serializer = Saxon.PROCESSOR.newSerializer(out);
            if (page.getValue().endsWith(".xq")) {
                serializer.setOutputProperty(METHOD, ext.equals("html") || ext.equals("xml") ? ext : "text");
                serializer.setOutputProperty(INDENT, "yes");
                serializer.setOutputProperty(HTML_VERSION, "4.0");
            }
            Saxon.render(page.getValue(), Data.get(), params, serializer);
            return out.toString();
        }
        return null;
    }

    private static String home(boolean pub) throws Exception {
        var omit = pub ? "dev" : "pub";
        var root = Data.get().doc().children(n -> n.getNodeKind() == XdmNodeKind.ELEMENT).iterator().next();
        var date = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG));
        return Files.readString(WEBAPP.resolve("index.html"))
            .replaceAll("(?s)<!--" + omit + "-->.*?<!--/" + omit + "-->\n?", "")
            .replaceAll("<!--/?(pub|dev)-->\n?", "")
            .replace("${version}", Functions.attr(root, "version"))
            .replace("${date}", date.replace(" ", "&#160;"))
            .replace("${year}", Year.now().toString());
    }

    /// Writes the published site: the static files, the home page and the pages listed by `site-pages.xq`
    private static void release(Path out) throws Exception {
        var start = System.currentTimeMillis();
        try (var files = Files.walk(WEBAPP)) {
            for (var source : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                var path = WEBAPP.relativize(source);
                if (path.startsWith("config") || path.startsWith("data") || path.getFileName().toString().startsWith(".")) continue;
                Files.createDirectories(out.resolve(path).getParent());
                Files.copy(source, out.resolve(path), REPLACE_EXISTING);
            }
        }
        Files.writeString(out.resolve("index.html"), home(true));
        var pages = render("/site-pages.txt", true, Map.of()).lines().toList();
        pages.parallelStream().forEach(page -> {
            try {
                var target = out.resolve("content").resolve(page);
                Files.createDirectories(target.getParent());
                Files.writeString(target, render("/" + page, true, Map.of()));
            } catch (Exception e) {
                throw new RuntimeException(page, e);
            }
        });
        IO.println("Wrote " + pages.size() + " pages to " + out + " in " + (System.currentTimeMillis() - start) + " ms");
    }

    private static void replaceData(String xsl) throws Exception {
        var tmp = Data.FILE.resolveSibling("eldamo-data.xml.tmp");
        Saxon.render(xsl, Data.get(), Map.of(), Saxon.PROCESSOR.newSerializer(tmp.toFile()));
        Files.move(tmp, Data.FILE, REPLACE_EXISTING);
    }
}
