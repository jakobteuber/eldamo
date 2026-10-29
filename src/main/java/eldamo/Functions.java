package eldamo;

import net.sf.saxon.s9api.*;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import java.io.StringReader;
import java.time.Year;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.sf.saxon.s9api.ItemType.*;
import static net.sf.saxon.s9api.OccurrenceIndicator.*;
import static net.sf.saxon.s9api.SequenceType.makeSequenceType;
import static net.sf.saxon.s9api.streams.Steps.child;

/// The `xdb:*` extension functions available to XQuery, XSLT and the key definitions in [Data].
final class Functions {

    static final String NS = "xdb";

    private static final SequenceType NODE = makeSequenceType(ANY_NODE, ZERO_OR_ONE);
    private static final SequenceType NODES = makeSequenceType(ANY_NODE, ZERO_OR_MORE);
    private static final SequenceType STRING_ = makeSequenceType(STRING, ZERO_OR_ONE);
    private static final SequenceType ITEMS = makeSequenceType(ANY_ITEM, ZERO_OR_MORE);
    private static final SequenceType BOOL = makeSequenceType(BOOLEAN, ONE);
    private static final SequenceType FUNCTION = makeSequenceType(ANY_FUNCTION, ONE);

    private interface Body {
        XdmValue call(XdmValue[] args) throws Exception;
    }

    static void register(Processor processor) {
        Stream.of(
            fn("currentYear", STRING_, Functions::currentYear),
            fn("delta", STRING_, Functions::delta, STRING_, STRING_),
            fn("isSame", BOOL, Functions::isSame, NODE, NODE),
            fn("contains", BOOL, Functions::contains, NODES, NODE),
            fn("distinct", NODES, Functions::distinct, NODES),
            fn("key", NODES, Functions::key, NODE, STRING_, STRING_),
            fn("matchAllKeys", NODES, Functions::matchAllKeys, NODE, STRING_, STRING_),
            fn("allReferenceCognates", NODES, Functions::allReferenceCognates, NODE),
            fn("allWordCognates", NODES, Functions::allWordCognates, NODE),
            fn("hashcode", STRING_, Functions::hashcode, NODE),
            fn("html", NODES, Functions::html, ITEMS),
            fn("memo", ITEMS, Functions::memo, STRING_, FUNCTION)
        ).forEach(processor::registerExtensionFunction);
    }

    private static ExtensionFunction fn(String name, SequenceType result, Body body, SequenceType... args) {
        return new ExtensionFunction() {
            public QName getName() { return new QName(NS, name); }
            public SequenceType getResultType() { return result; }
            public SequenceType[] getArgumentTypes() { return args; }
            public XdmValue call(XdmValue[] arguments) throws SaxonApiException {
                try {
                    return body.call(arguments);
                } catch (SaxonApiException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SaxonApiException(e);
                }
            }
        };
    }

    private static String str(XdmValue v) { return v.size() == 0 ? null : v.itemAt(0).getStringValue(); }
    private static XdmNode node(XdmValue v) { return v.size() == 0 ? null : (XdmNode) v.itemAt(0); }
    private static List<XdmNode> nodes(XdmValue v) { return v.stream().map(XdmNode.class::cast).toList(); }

    static String attr(XdmNode node, String name) {
        return Objects.requireNonNullElse(node.attribute(name), "");
    }

    private static XdmValue currentYear(XdmValue[] a) {
        return new XdmAtomicValue(Year.now().toString());
    }

    private static XdmValue delta(XdmValue[] a) {
        try {
            var compare = str(a[0]);
            var c1 = compare.toCharArray();
            var c2 = str(a[1]).toCharArray();
            var first = 0;
            while (first < c1.length && first < c2.length && c1[first] == c2[first]) first++;
            if (first < c1.length && c1[first] == 'ʰ') first--;
            var last = 0;
            while (last < c1.length && last < c2.length && c1[c1.length - last - 1] == c2[c2.length - last - 1]) last++;
            last = c1.length - last;
            if (last == first && c1.length > c2.length) last += c1.length - c2.length;
            return new XdmAtomicValue(last <= first ? "ø" : compare.substring(first, last));
        } catch (Exception e) {
            return new XdmAtomicValue("!!!");
        }
    }

    private static XdmValue isSame(XdmValue[] a) {
        return new XdmAtomicValue(node(a[0]) != null && node(a[0]).equals(node(a[1])));
    }

    private static XdmValue contains(XdmValue[] a) {
        return new XdmAtomicValue(nodes(a[0]).contains(node(a[1])));
    }

    private static XdmValue distinct(XdmValue[] a) {
        return new XdmValue(new LinkedHashSet<>(nodes(a[0])));
    }

    private static XdmValue key(XdmValue[] a) {
        return new XdmValue(lookup(str(a[1]), str(a[2])));
    }

    private static XdmValue matchAllKeys(XdmValue[] a) {
        var values = str(a[2]);
        if (values == null || values.isBlank()) return XdmEmptySequence.getInstance();
        var split = values.split(" ");
        var result = new ArrayList<>(lookup(str(a[1]), split[0]));
        for (var v : Arrays.copyOfRange(split, 1, split.length)) result.retainAll(lookup(str(a[1]), v));
        return new XdmValue(result);
    }

    private static XdmValue allReferenceCognates(XdmValue[] a) {
        var ref = node(a[0]);
        var lang = lang(ref);
        return new XdmValue(closure(ref, n -> Stream.concat(
            lookup("cognate-of-ref", attr(n, "source")).stream(),
            n.select(child("cognate")).flatMap(c -> lookup("ref", attr(c, "source")).stream())
        )).stream().filter(n -> !lang(n).equals(lang)).toList());
    }

    private static XdmValue allWordCognates(XdmValue[] a) {
        var word = node(a[0]);
        return new XdmValue(closure(word, n -> Stream.concat(
            lookup("cognate-of", lang(n) + ":" + attr(n, "v")).stream(),
            n.select(child("cognate")).flatMap(c -> lookup("lang-word", attr(c, "l") + ":" + attr(c, "v")).stream())
        )).stream().filter(n -> !n.equals(word)).toList());
    }

    private static XdmValue hashcode(XdmValue[] a) {
        var node = node(a[0]);
        return new XdmAtomicValue(node == null ? "" : switch (node.getNodeName().getLocalName()) {
            case "ref" -> toHash(attr(node, "source"));
            case "word" -> pageId(lang(node), attr(node, "v"));
            default -> "";
        });
    }

    private static XdmValue html(XdmValue[] a) throws Exception {
        var html = a[0].stream().map(XdmItem::getStringValue).collect(Collectors.joining());
        var db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        org.w3c.dom.Document doc;
        try {
            doc = db.parse(new InputSource(new StringReader("<root>" + html + "</root>")));
        } catch (SAXException e) {
            doc = db.newDocument();
            doc.appendChild(doc.createElement("root")).setTextContent("ERROR:BAD_HTML - " + e);
        }
        var links = doc.getElementsByTagName("a");
        for (var i = 0; i < links.getLength(); i++) {
            var link = (Element) links.item(i);
            var lang = link.getAttribute("l");
            if (!lang.isEmpty()) {
                var v = link.getAttribute("v");
                if (!v.isEmpty() && !link.hasChildNodes()) link.setTextContent(v);
                var href = "../words/word-" + pageId(lang, v) + ".html";
                var deeplink = link.getAttribute("deeplink");
                if (!deeplink.isEmpty()) href += "#" + deeplink;
                link.setAttribute("href", href);
                link.removeAttribute("deeplink");
                link.removeAttribute("l");
                link.removeAttribute("v");
            }
            var ref = link.getAttribute("ref");
            if (!ref.isEmpty()) {
                if (!link.hasChildNodes()) link.setTextContent(ref);
                link.removeAttribute("ref");
                link.setAttribute("href", "../references/ref-" + ref + ".html");
            }
        }
        var root = Saxon.PROCESSOR.newDocumentBuilder().build(new DOMSource(doc)).children().iterator().next();
        return new XdmValue(root.children());
    }

    /// The result of calling the function, computed once per key
    private static XdmValue memo(XdmValue[] a) throws SaxonApiException {
        var memo = Saxon.MEMO.get();
        var value = memo.get(str(a[0]));
        if (value == null) {
            value = ((XdmFunctionItem) a[1].itemAt(0)).call(Saxon.PROCESSOR);
            memo.putIfAbsent(str(a[0]), value);
        }
        return value;
    }

    private static List<XdmNode> lookup(String key, String value) {
        return value == null ? List.of() : Data.CURRENT.get().keys().getOrDefault(key, Map.of()).getOrDefault(value, List.of());
    }

    private static Set<XdmNode> closure(XdmNode start, Function<XdmNode, Stream<XdmNode>> neighbours) {
        var seen = new LinkedHashSet<>(List.of(start));
        var todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) neighbours.apply(todo.pop()).filter(seen::add).forEach(todo::add);
        return seen;
    }

    private static String lang(XdmNode element) {
        var lang = attr(element, "l");
        var parent = element.getParent();
        return lang.isEmpty() && parent.getNodeKind() == XdmNodeKind.ELEMENT ? lang(parent) : lang;
    }

    private static String pageId(String lang, String value) {
        var key = hashKey(lang, value);
        return Data.CURRENT.get().pageIds().getOrDefault(key, hashValue(key));
    }

    static String hashKey(String lang, String value) {
        return lang + "::" + value;
    }

    static String hashValue(String value) {
        var chars = value.toCharArray();
        Arrays.sort(chars);
        return toHash(value + new String(chars));
    }

    private static String toHash(String v) {
        return String.valueOf((long) v.hashCode() + Integer.MAX_VALUE);
    }
}
