xquery version "3.1" encoding "UTF-8";

declare namespace xquery="http://basex.org/modules/xquery";
declare namespace csv="http://basex.org/modules/csv";

(: --- helpers -------------------------------------------------------------- :)

(: split a string on a literal (non-regex) separator :)
declare function local:split-str($s as xs:string, $sep as xs:string) as xs:string* {
  if ($sep = "") then $s
  else if (fn:contains($s, $sep))
    then (fn:substring-before($s, $sep), local:split-str(fn:substring-after($s, $sep), $sep))
    else $s
};

(: parse a comma-separated list of column names, trimming whitespace and blanks :)
declare function local:cols($s as xs:string?) as xs:string* {
  for $c in fn:tokenize(($s, "")[1], ",")
    let $t := fn:normalize-space($c)
    where $t ne ""
    return $t
};

(: the "effective boolean value", extended to treat empty maps/arrays as false :)
declare function local:ebv($item as item()*) as xs:boolean {
  if (fn:count($item) gt 1) then
    let $ones := for $e in $item return if (local:ebv($e)) then 1 else ()
    return fn:count($ones) gt 0
  else if ($item instance of map(*)) then map:size($item) gt 0
  else if ($item instance of array(*)) then array:size($item) gt 0
  else fn:boolean($item)
};

(: evaluate a per-row XQuery expression, exposing the row as a map so that :)
(: `?column` lookups (and xtra: functions) work, as in the other transformers :)
declare function local:eval-row(
  $expr as xs:string?,
  $record as element(record),
  $libURI as xs:anyURI
) as item()* {
  if ($expr) then
    let $row := map:merge(for $e in $record/entry return map:entry($e/@name/fn:string(), $e/fn:string()))
    return
      if (fn:exists($libURI) and fn:contains($expr, "xtra")) then
        xquery:eval("import module namespace xtra = ""xtra"" at """ || $libURI || """;" || $expr, map { "": $row })
      else
        xquery:eval($expr, map { "": $row })
  else ()
};

(: --- operations (each: element(csv) -> element(csv)) ---------------------- :)

(: keep and reorder the named columns :)
declare function local:op-select($grid as element(csv), $cols as xs:string*) as element(csv) {
  <csv>{
    for $r in $grid/record return
      <record>{
        for $c in $cols
          return let $e := $r/entry[@name = $c] return if ($e) then $e else <entry name="{$c}"/>
      }</record>
  }</csv>
};

(: remove the named columns :)
declare function local:op-drop($grid as element(csv), $cols as xs:string*) as element(csv) {
  <csv>{ for $r in $grid/record return <record>{ $r/entry[fn:not(@name = $cols)] }</record> }</csv>
};

(: rename a column :)
declare function local:op-rename($grid as element(csv), $from as xs:string, $to as xs:string) as element(csv) {
  <csv>{
    for $r in $grid/record return
      <record>{
        for $e in $r/entry
          return if ($e/@name = $from) then <entry name="{$to}">{ $e/node() }</entry> else $e
      }</record>
  }</csv>
};

(: keep rows for which the expression is true :)
declare function local:op-filter($grid as element(csv), $expr as xs:string?, $libURI as xs:anyURI) as element(csv) {
  <csv>{
    for $r in $grid/record
      where local:ebv(local:eval-row($expr, $r, $libURI))
      return $r
  }</csv>
};

(: append a computed column :)
declare function local:op-derive($grid as element(csv), $name as xs:string, $expr as xs:string?, $libURI as xs:anyURI) as element(csv) {
  <csv>{
    for $r in $grid/record return
      <record>{
        $r/entry,
        <entry name="{$name}">{ fn:string-join(local:eval-row($expr, $r, $libURI) ! fn:string(.), "") }</entry>
      }</record>
  }</csv>
};

(: join several columns into one (removing the sources) :)
declare function local:op-merge($grid as element(csv), $cols as xs:string*, $to as xs:string, $sep as xs:string) as element(csv) {
  <csv>{
    for $r in $grid/record return
      <record>{
        $r/entry[fn:not(@name = $cols)],
        <entry name="{$to}">{ fn:string-join($r/entry[@name = $cols] ! fn:string(.), $sep) }</entry>
      }</record>
  }</csv>
};

(: split one column into several fixed columns :)
declare function local:op-split($grid as element(csv), $col as xs:string, $into as xs:string*, $sep as xs:string) as element(csv) {
  <csv>{
    for $r in $grid/record
      let $parts := local:split-str(fn:string($r/entry[@name = $col]), $sep)
      return
        <record>{
          $r/entry[fn:not(@name = $col)],
          for $i in 1 to fn:count($into) return <entry name="{$into[$i]}">{ $parts[$i] }</entry>
        }</record>
  }</csv>
};

(: expand a multi-valued column into one row per value; an empty cell :)
(: preserves the record as a single row with an empty value :)
declare function local:op-explode($grid as element(csv), $col as xs:string, $sep as xs:string, $to as xs:string?) as element(csv) {
  let $name := ($to[. ne ""], $col)[1]
  return
    <csv>{
      for $r in $grid/record
        let $vals := local:split-str(fn:string($r/entry[@name = $col]), $sep)
        for $v in $vals return
          <record>{
            for $e in $r/entry
              return if ($e/@name = $col) then <entry name="{$name}">{ $v }</entry> else $e
          }</record>
    }</csv>
};

(: dispatch one pipeline step :)
declare function local:apply(
  $op as element(record),
  $grid as element(csv),
  $arr-sep as xs:string,
  $libURI as xs:anyURI
) as element(csv) {
  let $name := fn:string($op/op)
  let $cols := local:cols($op/columns)
  let $to := local:cols($op/to)
  let $expr := $op/expr/text()
  let $sep := ($expr, $arr-sep)[1]
  return try {
    switch ($name)
      case "select"  return local:op-select($grid, $cols)
      case "drop"    return local:op-drop($grid, $cols)
      case "rename"  return local:op-rename($grid, $cols[1], $to[1])
      case "filter"  return local:op-filter($grid, $expr, $libURI)
      case "derive"  return local:op-derive($grid, $to[1], $expr, $libURI)
      case "merge"   return local:op-merge($grid, $cols, $to[1], $sep)
      case "split"   return local:op-split($grid, $cols[1], $to, $sep)
      case "explode" return local:op-explode($grid, $cols[1], $sep, $to[1])
      default return fn:error(xs:QName("mapping-error"), "Unknown op: " || $name)
  } catch * {
    fn:error(xs:QName("mapping-error"), $err:code || " in op '" || $name || "': " || $err:description)
  }
};

(: --- main ----------------------------------------------------------------- :)

declare variable $input as xs:string external;
declare variable $config as xs:string external;
declare variable $options as map(xs:string, xs:string) external;
declare variable $libURI as xs:anyURI external;

let $in-sep := (map:get($options, "input.separator"), "comma")[1]
let $in-header := if (map:get($options, "input.header") = ("no", "false")) then "no" else "yes"
let $out-sep := (map:get($options, "output.separator"), $in-sep)[1]
let $out-header := if ((map:get($options, "output.header"), $in-header)[1] = ("no", "false")) then "no" else "yes"
let $arr-sep := (map:get($options, "array.separator"), "||")[1]

let $grid := csv:parse($input, map { "separator": $in-sep, "header": $in-header, "format": "attributes" })/csv
let $ops := csv:parse($config, map { "separator": "tab", "header": "yes", "quotes": "no" })/csv/record
let $result := fn:fold-left($ops, $grid, function($g, $op) { local:apply($op, $g, $arr-sep, $libURI) })
return csv:serialize($result, map { "separator": $out-sep, "header": $out-header, "format": "attributes" })
