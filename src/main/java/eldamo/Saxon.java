package eldamo;

import net.sf.saxon.s9api.*;

import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/// Runs the XQuery and XSLT files, recompiling them after any of them changed.
final class Saxon {

    static final Processor PROCESSOR = new Processor(false);
    private static final Path QUERIES = Main.WEBAPP.resolve("config/query-configs");
    private static final Map<String, Object> compiled = new HashMap<>();
    private static long compiledAt;

    static {
        Functions.register(PROCESSOR);
    }

    static void render(String file, Data.Snapshot data, Map<String, String> params, Serializer out) throws Exception {
        var exe = compile(file);
        ScopedValue.where(Data.CURRENT, data).call(() -> {
            switch (exe) {
                case XQueryExecutable xq -> {
                    var query = xq.load();
                    query.setContextItem(data.doc());
                    params.forEach((k, v) -> query.setExternalVariable(new QName(k), new XdmAtomicValue(v)));
                    query.run(out);
                }
                case XsltExecutable xsl -> {
                    var transformer = xsl.load30();
                    var values = new HashMap<QName, XdmValue>();
                    params.forEach((k, v) -> values.put(new QName(k), new XdmAtomicValue(v)));
                    transformer.setStylesheetParameters(values);
                    transformer.transform(data.doc().asSource(), out);
                }
                default -> throw new IllegalStateException();
            }
            return null;
        });
    }

    private static synchronized Object compile(String file) throws SaxonApiException, IOException {
        var latest = lastModified();
        if (latest > compiledAt) {
            compiled.clear();
            compiledAt = latest;
        }
        var exe = compiled.get(file);
        if (exe == null) {
            var path = QUERIES.resolve(file).toFile();
            if (file.endsWith(".xq")) {
                var compiler = PROCESSOR.newXQueryCompiler();
                compiler.declareNamespace("xdb", Functions.NS);
                exe = compiler.compile(path);
            } else {
                exe = PROCESSOR.newXsltCompiler().compile(new StreamSource(path));
            }
            compiled.put(file, exe);
        }
        return exe;
    }

    private static long lastModified() throws IOException {
        try (var files = Files.list(QUERIES)) {
            return files.mapToLong(f -> f.toFile().lastModified()).max().orElse(0);
        }
    }
}
