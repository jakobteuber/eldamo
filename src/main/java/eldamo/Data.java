package eldamo;

import net.sf.saxon.s9api.*;
import org.xml.sax.*;
import org.xml.sax.helpers.XMLFilterImpl;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.Executors;

import static eldamo.Functions.attr;
import static net.sf.saxon.s9api.streams.Steps.descendant;

/// The lexicon data, with lookup indexes similar to XSLT keys. Reloaded when the file changes.
final class Data {

    static final Path FILE = Main.WEBAPP.resolve("data/eldamo-data.xml");
    static final Path OLD = Main.WEBAPP.resolve("data/eldamo-data-old.xml");

    private enum Type { NODES, STRING, LIST }

    private record Key(String name, String match, Type type, String use) {}

    private static final List<Key> KEYS = List.of(
        new Key("ref", "ref", Type.NODES, "@source"),
        new Key("word", "word", Type.NODES, "@v"),
        new Key("lang-word", "word", Type.STRING, "concat(ancestor-or-self::*[@l][1]/@l, ':', @v)"),
        new Key("language", "word", Type.NODES, "ancestor-or-self::*[@l][1]/@l"),
        new Key("category", "word", Type.NODES, "ancestor-or-self::*[@cat][1]/@cat"),
        new Key("cat-def", "cat", Type.NODES, "@id"),
        new Key("class", "word", Type.LIST, "normalize-space(concat(class/@form, ' ', class/@variant))"),
        new Key("cognate-of", "word", Type.LIST, "cognate/concat(@l, ':', @v)"),
        new Key("cognate-of-ref", "ref", Type.NODES, "cognate/@source"),
        new Key("related-to", "word", Type.NODES, "related/@v"),
        new Key("related-to-ref", "ref", Type.NODES, "related/@source"),
        new Key("before-to", "word", Type.NODES, "before/@v"),
        new Key("element-in", "word", Type.NODES, "element/@v"),
        new Key("element-in-ref", "ref", Type.NODES, "element/@source"),
        new Key("deriv-to", "word", Type.NODES, "deriv/@v"),
        new Key("deriv-to-ref", "ref", Type.NODES, "deriv/@source"),
        new Key("inflect-to-ref", "ref", Type.NODES, "inflect/@source"),
        new Key("inflect-form-ref", "ref", Type.LIST, "inflect/@form"),
        new Key("inflect-form-and-class-ref", "ref", Type.LIST,
            "normalize-space(concat(inflect/@form, ' ', inflect/@variant, ' ', ../class/@form, ' ', ../class/@variant))"),
        new Key("inflect-table", "inflect-table", Type.NODES, "@form"),
        new Key("rule-to", "rule", Type.STRING, "concat(@l, ':', @rule, ':', @from)"),
        new Key("rule-element-ref", "ref", Type.STRING, "concat(@rl, ':', @rule, ':', @from)"),
        new Key("rule-to-ref", "ref", Type.NODES, "deriv[../@rule]/@source"),
        new Key("rule-example-ref", "rule-example", Type.STRING, "concat(@l, ':', @v)"),
        new Key("rule-example-ref2", "rule-example", Type.STRING, "concat(@l, ':', @rule, ':', @from)"),
        new Key("correction", "ref", Type.NODES, "correction/@source"),
        new Key("example", "ref", Type.NODES, "example/@source"),
        new Key("example-inflect", "ref", Type.NODES, "example-inflect/@source"),
        new Key("example-deriv", "ref", Type.NODES, "example-deriv/@source"),
        new Key("word-code", "word", Type.STRING, "xdb:hashcode(.)"),
        new Key("ref-code", "ref", Type.STRING, "xdb:hashcode(.)"),
        new Key("page-id", "word", Type.NODES, "@page-id"),
        new Key("deprecated-to", "deprecated", Type.NODES, "@v")
    );

    record Snapshot(XdmNode doc, Map<String, String> pageIds, Map<String, Map<String, List<XdmNode>>> keys) {}

    /// The data a query is running against, for the extension functions
    static final ScopedValue<Snapshot> CURRENT = ScopedValue.newInstance();

    private static Snapshot current;
    private static FileTime loaded;

    /// Requests during a reload wait for it, so that a page requested after an edit shows it
    static synchronized Snapshot get() throws Exception {
        var modified = Files.getLastModifiedTime(FILE);
        if (!modified.equals(loaded)) {
            var start = System.currentTimeMillis();
            var doc = Saxon.PROCESSOR.newDocumentBuilder().build(new SAXSource(new TrimText(), new InputSource(FILE.toUri().toString())));
            var withoutKeys = new Snapshot(doc, pageIds(doc), Map.of());
            current = new Snapshot(doc, withoutKeys.pageIds(), index(withoutKeys));
            loaded = modified;
            IO.println("Loaded " + FILE + " in " + (System.currentTimeMillis() - start) + " ms");
        }
        return current;
    }

    /// Trims all text and drops comments, as the queries were written against such a tree
    private static final class TrimText extends XMLFilterImpl {
        private final StringBuilder text = new StringBuilder();

        TrimText() throws Exception {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            setParent(factory.newSAXParser().getXMLReader());
        }

        @Override
        public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
            if (!name.equals("http://xml.org/sax/properties/lexical-handler")) super.setProperty(name, value);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            flush();
            super.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            flush();
            super.endElement(uri, localName, qName);
        }

        private void flush() throws SAXException {
            var trimmed = text.toString().trim();
            text.setLength(0);
            if (!trimmed.isEmpty()) super.characters(trimmed.toCharArray(), 0, trimmed.length());
        }
    }

    private static Map<String, String> pageIds(XdmNode doc) {
        var words = doc.select(descendant("word")).toList();
        var ids = new HashMap<String, String>();
        for (var w : words) {
            if (!attr(w, "page-id").isEmpty()) ids.put(Functions.hashKey(attr(w, "l"), attr(w, "v")), attr(w, "page-id"));
        }
        var used = new HashSet<>(ids.values());
        for (var w : words) {
            if (!attr(w, "page-id").isEmpty()) continue;
            var key = Functions.hashKey(attr(w, "l"), attr(w, "v"));
            var id = Functions.hashValue(key);
            while (!used.add(id)) id = String.valueOf(Long.parseLong(id) + 1);
            ids.put(key, id);
        }
        return ids;
    }

    private static Map<String, Map<String, List<XdmNode>>> index(Snapshot data) throws Exception {
        try (var pool = Executors.newWorkStealingPool()) {
            var tasks = KEYS.stream().map(key -> pool.submit(() -> index(data, key))).toList();
            var result = new HashMap<String, Map<String, List<XdmNode>>>();
            for (var i = 0; i < KEYS.size(); i++) result.put(KEYS.get(i).name(), tasks.get(i).get());
            return result;
        }
    }

    private static Map<String, List<XdmNode>> index(Snapshot data, Key key) throws SaxonApiException {
        var compiler = Saxon.PROCESSOR.newXPathCompiler();
        compiler.declareNamespace("xdb", Functions.NS);
        var selector = compiler.compile("descendant::%s ! [., (%s) ! string()]".formatted(key.match(), key.use())).load();
        selector.setContextItem(data.doc());
        var index = new HashMap<String, List<XdmNode>>();
        for (var item : ScopedValue.where(CURRENT, data).call(selector::evaluate)) {
            var entry = (XdmArray) item;
            var values = entry.get(1);
            var first = values.size() == 0 ? "" : values.itemAt(0).getStringValue();
            var ids = switch (key.type()) {
                case NODES -> values.stream().map(XdmItem::getStringValue).toList();
                case STRING -> List.of(first);
                case LIST -> List.of(first.trim().split(" "));
            };
            for (var id : ids) {
                if (!id.isEmpty()) index.computeIfAbsent(id, _ -> new ArrayList<>()).add((XdmNode) entry.get(0));
            }
        }
        return index;
    }
}
