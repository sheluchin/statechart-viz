(ns statechart-viz.explorer
  "A chart and the charts it invokes as one clickable page: click a container
  to zoom into it, click a state that invokes a chart to open that chart, and
  click a `↗ stub` to step out to the state that holds both ends. A
  breadcrumb, or Escape, goes back.

  Every view is rendered here, ahead of time, so the page needs no Graphviz
  and no runtime: its script only swaps SVGs and reads the ids `dot` gives
  each state (`state:<key>`), invoke box (`invoke:<src>`) and stub
  (`stub:<key>`).

    (plan chart opts)     the views and where each click goes, as data
    (fragment chart opts) the explorer as HTML to put in a page
    (page chart opts)     a whole HTML page

  Options:
    :charts     {src chart}: the charts invoked, by their invoke's `:src`.
                One that isn't here is drawn but not opened
    :title      the root chart's name; default its :diagram/label or \"chart\"
    :theme      theme overrides, as for `dot`
    :dark       a second theme: rendered too, and shown when the viewer's
                page is dark (`prefers-color-scheme`, or data-theme=\"dark\"
                on <html>)
    :depth, :max-loops   as for `dot`, for every view"
  (:require
   [clojure.string :as str]
   [statechart-viz.dot :as d]
   [statechart-viz.model :as m]
   [statechart-viz.render :as r]
   [statechart-viz.theme :as theme]))

;; ---------------------------------------------------------------------------
;; The plan: views, and where each click in them goes

(defn- chart-name [chart fallback]
  (or (:diagram/label chart) fallback))

(defn- charts-reached
  "`[key chart name]` for the root and each chart it invokes, transitively,
  that `charts` has: the root first, each once."
  [root charts title]
  (loop [todo [[nil root (or title (chart-name root "chart"))]] seen {} out []]
    (if-let [[src chart nm] (first todo)]
      (if (contains? seen src)
        (recur (rest todo) seen out)
        (let [k     (str "c" (count out))
              found (for [node (vals (m/index (m/tree chart)))
                          iv   (:invokes node)
                          :when (and (contains? charts (:src iv)) (not (contains? seen (:src iv))))]
                      [(:src iv) (get charts (:src iv)) (chart-name (get charts (:src iv)) (:label iv))])]
          (recur (concat (rest todo) found) (assoc seen src k) (conj out [k chart nm src]))))
      {:charts out :key-of seen})))

(defn- sole-top?
  "The chart's only top-level state: zoomed into, it draws as the whole chart does."
  [idx id]
  (and (= :ROOT (:parent (get idx id))) (= 1 (count (:children (get idx :ROOT))))))

(defn- view-key [chart-key focus idx]
  (if (or (= :ROOT focus) (sole-top? idx focus)) chart-key (str chart-key "." (:key (get idx focus)))))

(defn- containing
  "The innermost container holding both `focus` and `id`, or :ROOT."
  [idx focus id]
  (let [around (set (cons focus (m/ancestors-of idx focus)))]
    (or (first (filter around (m/ancestors-of idx id))) :ROOT)))

(defn- goes
  "svg id -> {:view :hint} for the view of `chart` focused on `focus`: its
  containers zoom in, its invoking states open their chart, its stubs step
  out. Only ids the view's DOT draws are kept."
  [{:keys [idx chart-key key-of]} focus dot-text]
  (let [inside? #(or (= :ROOT focus) (m/descendant? idx % focus))
        drawn?  #(str/includes? dot-text (str "id=\"" % "\""))
        vk      #(view-key chart-key % idx)]
    (into {}
          (filter (fn [[id {:keys [view]}]] (and (drawn? id) (not= view (vk focus)))))
          (concat
           (for [node (vals idx)
                 :when (and (not= :root (:kind node)) (inside? (:id node)))
                 :let [opens (some #(when-let [k (get key-of (:src %))] [k %]) (:invokes node))]
                 e (cond-> []
                     (m/compound? node) (conj [(d/svg-id node) {:view (vk (:id node)) :hint (str "Zoom into " (:label node))}])
                     (and opens (m/compound? node))
                     (conj [(str "invoke:" (:src (second opens))) {:view (first opens) :hint (str "Open " (:label (second opens)))}])
                     (and opens (not (m/compound? node)))
                     (conj [(d/svg-id node) {:view (first opens) :hint (str "Open " (:label (second opens)))}]))]
             e)
           (for [node (vals idx)
                 :when (and (not= :root (:kind node)) (not (inside? (:id node))) (not= focus (:id node)))
                 :let [to (containing idx focus (:id node))]]
             [(str "stub:" (:key node)) {:view (vk to) :hint (str "Out to " (if (= :ROOT to) "the whole chart" (:label (get idx to))))}])))))

(defn plan
  "The explorer's views, before rendering: `{:start view-key :views {view-key
  {:chart :focus :title :label :dot :go {svg-id {:view :hint}}}}}`, a view
  for each chart reached and each container in it."
  [chart {:keys [charts title] :as opts}]
  (let [{:keys [charts key-of]} (charts-reached chart (or charts {}) title)
        dot-opts (select-keys opts [:depth :max-loops :theme])]
    {:start "c0"
     :views (into {}
                  (for [[ck c nm] charts
                        :let [idx (m/index (m/tree c))
                              ctx {:idx idx :chart-key ck :key-of key-of}]
                        focus (cons :ROOT (->> (vals idx) (filter #(and (not= :root (:kind %)) (m/compound? %) (not (sole-top? idx (:id %)))))
                                               (sort-by (juxt :depth :key)) (map :id)))
                        ;; the page's breadcrumb names a zoomed view; only a chart's own view is titled
                        :let [title    (when (= :ROOT focus) nm)
                              dot-text (d/dot c (assoc dot-opts :focus focus :title title))]]
                    [(view-key ck focus idx)
                     {:chart c :focus focus :title title :dot dot-text :go (goes ctx focus dot-text)
                      :label (if (= :ROOT focus) (if (= "c0" ck) nm (str "⤵ " nm)) (:label (get idx focus)))}]))}))

;; ---------------------------------------------------------------------------
;; Rendering, and the page

(defn- json
  "JSON for strings, keywords, numbers, maps and sequences. `<` is escaped, so
  it is safe inside a <script>."
  [x]
  (cond (map? x)     (str "{" (str/join "," (for [[k v] x] (str (json (if (keyword? k) (name k) (str k))) ":" (json v)))) "}")
        (string? x)  (str "\""
                          (str/replace x #"[\"\\\x00-\x1f<>&  ]"
                                       #(case %
                                          "\"" "\\\"" "\\" "\\\\" "\n" "\\n" "\t" "\\t" "\r" "\\r"
                                          (format "\\u%04x" (int (first %)))))
                          "\"")
        (keyword? x) (json (name x))
        (nil? x)     "null"
        (number? x)  (str x)
        (sequential? x) (str "[" (str/join "," (map json x)) "]")
        :else        (json (str x))))

(defn- svg-of
  "SVG text for one view, from its own DOT with `theme`; a throw if Graphviz fails."
  [{:keys [chart focus title]} opts theme]
  (let [{:keys [ok error]} (r/render-dot (d/dot chart (assoc (select-keys opts [:depth :max-loops]) :focus focus :theme theme :title title))
                                         {:format :svg})]
    (when error (throw (ex-info (str "Graphviz failed on a view: " error) {:focus focus})))
    (let [s (String. ^bytes ok "UTF-8")] (subs s (str/index-of s "<svg")))))

(defn data
  "The explorer's data, rendered: what the page's script reads."
  [chart opts]
  (let [{:keys [start views]} (plan chart opts)]
    {:start start
     :views (into {}
                  (for [[k {:keys [label go] :as v}] views]
                    [k {:label label
                        :go    go
                        :svg   (cond-> {:light (svg-of v opts (:theme opts))}
                                 (:dark opts) (assoc :dark (svg-of v opts (merge (:theme opts) (:dark opts)))))}]))}))

(def ^:private style
  ".sv-explorer{display:grid;gap:8px;min-width:0}
.sv-explorer .sv-bar{display:flex;flex-wrap:wrap;gap:6px 12px;align-items:center;justify-content:space-between;font:13px ui-monospace,Menlo,monospace}
.sv-explorer .sv-crumbs{display:flex;flex-wrap:wrap;gap:4px;align-items:center;min-width:0}
.sv-explorer .sv-crumbs button,.sv-explorer .sv-size{font:inherit;color:inherit;background:none;border:1px solid transparent;border-radius:6px;padding:2px 6px;cursor:pointer}
.sv-explorer .sv-crumbs button:hover,.sv-explorer .sv-size:hover{border-color:currentColor}
.sv-explorer .sv-crumbs button[aria-current]{font-weight:600;cursor:default;border-color:transparent}
.sv-explorer .sv-crumbs .sep{opacity:.5}
.sv-explorer .sv-stage{overflow:auto}
.sv-explorer .sv-stage svg{display:block;height:auto;margin:0 auto}
.sv-explorer .sv-stage.fit svg{width:100%}
.sv-explorer .sv-go{cursor:pointer}
.sv-explorer .sv-go:hover>path,.sv-explorer .sv-go:hover>polygon,.sv-explorer .sv-go:focus>path,.sv-explorer .sv-go:focus>polygon{stroke-width:3.5}
.sv-explorer .sv-go:hover>text,.sv-explorer .sv-go:focus>text{text-decoration:underline}
.sv-explorer .sv-go:focus{outline:none}
.sv-explorer .sv-hint{opacity:.7;font-size:12px}")

(def ^:private script
  "(function(root){
var data=JSON.parse(root.querySelector('script[type=\"application/json\"]').textContent);
var stage=root.querySelector('.sv-stage'),crumbs=root.querySelector('.sv-crumbs'),size=root.querySelector('.sv-size');
var stack=[data.start];
function dark(){var t=document.documentElement.getAttribute('data-theme');if(t)return t==='dark';return window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches;}
function go(k){var i=stack.indexOf(k);if(i>=0)stack=stack.slice(0,i+1);else stack.push(k);show();}
function show(){
  var v=data.views[stack[stack.length-1]];
  stage.innerHTML=(dark()&&v.svg.dark)||v.svg.light;
  var svg=stage.querySelector('svg');
  if(svg){svg.style.maxWidth=svg.getAttribute('width');svg.removeAttribute('width');svg.removeAttribute('height');}
  Object.keys(v.go).forEach(function(id){
    var el=document.getElementById(id);if(!el||!stage.contains(el))return;
    var g=v.go[id];el.classList.add('sv-go');el.setAttribute('data-go',g.view);el.setAttribute('tabindex','0');el.setAttribute('role','link');el.setAttribute('aria-label',g.hint);
    var t=el.querySelector('title');if(t)t.textContent=g.hint;});
  crumbs.innerHTML='';
  stack.forEach(function(k,i){
    if(i){var s=document.createElement('span');s.className='sep';s.textContent='›';crumbs.appendChild(s);}
    var b=document.createElement('button');b.type='button';b.textContent=data.views[k].label;
    if(i===stack.length-1)b.setAttribute('aria-current','page');else b.addEventListener('click',function(){go(k);});
    crumbs.appendChild(b);});
}
stage.addEventListener('click',function(e){var el=e.target.closest&&e.target.closest('[data-go]');if(el)go(el.getAttribute('data-go'));});
stage.addEventListener('keydown',function(e){var el=e.target.closest&&e.target.closest('[data-go]');if(el&&(e.key==='Enter'||e.key===' ')){e.preventDefault();go(el.getAttribute('data-go'));}});
// Escape goes to the explorer used last: a click replaces the SVG, so focus can't say which
root.addEventListener('pointerdown',function(){window.svExplorerLast=root;});
root.addEventListener('focusin',function(){window.svExplorerLast=root;});
document.addEventListener('keydown',function(e){if(e.key==='Escape'&&stack.length>1&&(window.svExplorerLast||root)===root){stack.pop();show();}});
size.addEventListener('click',function(){var fit=stage.classList.toggle('fit');size.textContent=fit?'Actual size':'Fit';});
if(window.matchMedia)matchMedia('(prefers-color-scheme: dark)').addEventListener('change',show);
new MutationObserver(show).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
show();
})(document.currentScript.parentNode);")

(defn fragment
  "The explorer as an HTML fragment: a <div class=\"sv-explorer\"> with its
  style, data and script, to put in a page. Several can share a page."
  [chart opts]
  (str "<div class=\"sv-explorer\">\n"
       "<style>" style "</style>\n"
       "<div class=\"sv-bar\"><nav class=\"sv-crumbs\" aria-label=\"Where you are\"></nav>"
       "<span><span class=\"sv-hint\">click a box to open it · Esc goes back</span> "
       "<button type=\"button\" class=\"sv-size\">Actual size</button></span></div>\n"
       "<div class=\"sv-stage fit\"></div>\n"
       "<script type=\"application/json\">" (json (data chart opts)) "</script>\n"
       "<script>" script "</script>\n"
       "</div>\n"))

(defn page
  "A whole HTML page holding the explorer."
  [chart opts]
  (let [title (or (:title opts) (chart-name chart "Statechart"))
        light (theme/resolve-theme (:theme opts))
        dark  (when (:dark opts) (theme/resolve-theme (merge (:theme opts) (:dark opts))))]
    (str "<!doctype html>\n<html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>" (str/escape (str title) {\< "&lt;" \> "&gt;" \& "&amp;"}) "</title>"
         "<style>body{margin:0;padding:16px;font:14px system-ui,sans-serif;background:" (:background light) ";color:" (:text light) "}"
         (when dark (str "@media (prefers-color-scheme: dark){body{background:" (:background dark) ";color:" (:text dark) "}}"))
         "</style></head><body>\n"
         (fragment chart opts)
         "</body></html>\n")))
