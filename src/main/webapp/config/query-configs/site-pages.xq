import module namespace c = "common.xq" at "common.xq";

(: Every page of the published site, relative to /content. The index pages are those linked by language-page.xq :)

declare variable $root := /*;

declare function local:get-speech($word as element()?) as xs:string? {
    $word/ancestor-or-self::*[@speech][1]/@speech/string()
};

declare function local:language-pages($id as xs:string) as xs:string* {
    let $is-neo-lang := $id = ('nq', 'ns', 'np')
    let $all := c:lang-words($root, $id)
    let $roots := $all[local:get-speech(.)='root']
    let $words := $all
        [not(ends-with(local:get-speech(.), '-name'))]
        [not(local:get-speech(.)='phrase' or local:get-speech(.)='text')]
        [not(starts-with(local:get-speech(.), 'phone'))]
        [not(local:get-speech(.)='grammar')]
        [not(local:get-speech(.)='root')]
    let $names := $all[ends-with(local:get-speech(.), '-name')][not($is-neo-lang)]
    let $phrases := $all[local:get-speech(.)='phrase' or local:get-speech(.)='text']
    let $phonetics := $all[starts-with(local:get-speech(.), 'phone')][not($is-neo-lang)]
    let $grammar := $all[local:get-speech(.)='grammar'][not($is-neo-lang)]
    let $categories := ($words | $roots)[ancestor-or-self::*[@cat and not(@cat='?')]]
    return (
        concat('language-pages/lang-', $id, '.html'),
        if ($is-neo-lang) then concat('vocabulary-indexes/vocabulary-words-', $id, '.html') else (),
        if ($roots) then concat('root-indexes/roots-', $id, '.html') else (),
        if ($words) then concat('word-indexes/words-', $id, '.html') else (),
        if ($names) then concat('name-indexes/names-', $id, '.html') else (),
        if ((not($is-neo-lang) and $phrases) or ($is-neo-lang and $phrases[@l = ('nq', 'ns')]))
            then concat('phrase-indexes/phrases-', $id, '.html') else (),
        if ($grammar) then concat('grammar-indexes/grammars-', $id, '.html') else (),
        if ($phonetics) then concat('phonetic-indexes/phonetics-', $id, '.html') else (),
        if ($categories) then concat('category-indexes/categories-', $id, '.html') else (),
        if ($is-neo-lang) then concat('neologism-indexes/neologisms-', $id, '.html') else (),
        if ($is-neo-lang) then concat('deprecation-indexes/deprecations-', $id, '.html') else ()
    )
};

string-join((
    'languages/index.html',
    'references/index.html',
    'search/search.html',
    'search/advanced-search.html',
    'search/search-index.js',
    'search/search-adv-index.js',
    'data-model/eldamo-data.xml',
    for $id in distinct-values(/*/language-cat//language/@id) return local:language-pages($id),
    for $prefix in distinct-values(/*/source/@prefix) return concat('references/ref-', $prefix, '.html'),
    for $word in //word return concat('words/word-', xdb:hashcode($word), '.html')
), '&#10;')
