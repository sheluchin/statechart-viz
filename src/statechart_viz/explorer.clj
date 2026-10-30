(ns statechart-viz.explorer
  "A chart and the charts it invokes as one clickable page: click a container
  to zoom into it, click a state that invokes a chart to open that chart, and
  click a `↗ stub` to step out to the state that holds both ends. A
  breadcrumb, or Escape, goes back. The wheel zooms at the pointer (at the
  fitted size, scrolling down scrolls the page instead), a drag pans, and
  − + Fit, or the keys - + 0, do the same from the keyboard or a phone.

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
   [statechart-viz.nav :as nav]
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

(defn- view-key [chart-key chart focus idx]
  (let [f (nav/view-focus chart focus)]
    (if (= :ROOT f) chart-key (str chart-key "." (:key (get idx f))))))

(defn- goes
  "svg id -> {:view :hint} for the view of `chart` focused on `focus`: its
  moves (`statechart-viz.nav/moves`) as clicks. Only ids the view's DOT draws,
  and charts the explorer has, are kept."
  [{:keys [idx chart chart-key key-of]} focus dot-text]
  (let [{:keys [in open stubs]} (nav/moves chart focus)
        drawn? #(str/includes? dot-text (str "id=\"" % "\""))
        vk     #(view-key chart-key chart % idx)]
    (into {}
          (filter (fn [[id _]] (drawn? id)))
          (concat
           (for [{:keys [id label svg-id]} in] [svg-id {:view (vk id) :hint (str "Zoom into " label)}])
           ;; a state invoking several charts opens the first the explorer has
           (reverse (for [{:keys [src label svg-id]} open :when (contains? key-of src)]
                      [svg-id {:view (get key-of src) :hint (str "Open " label)}]))
           (for [{:keys [to svg-id]} stubs]
             [svg-id {:view (vk to) :hint (str "Out to " (if (= :ROOT to) "the whole chart" (:label (get idx to))))}])))))

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
                              ctx {:idx idx :chart c :chart-key ck :key-of key-of}]
                        focus (cons :ROOT (map :id (:in (nav/moves c :ROOT))))
                        ;; the page's breadcrumb names a zoomed view; only a chart's own view is titled
                        :let [title    (when (= :ROOT focus) nm)
                              dot-text (d/dot c (assoc dot-opts :focus focus :title title))]]
                    [(view-key ck c focus idx)
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
.sv-explorer .sv-crumbs,.sv-explorer .sv-zoom{display:flex;flex-wrap:wrap;gap:4px;align-items:center;min-width:0}
.sv-explorer button{font:inherit;color:inherit;background:none;border:1px solid transparent;border-radius:6px;padding:2px 6px;cursor:pointer}
.sv-explorer button:hover:not(:disabled){border-color:currentColor}
.sv-explorer button:disabled{opacity:.4;cursor:default}
.sv-explorer button:focus-visible{outline:2px solid currentColor;outline-offset:1px}
.sv-explorer .sv-crumbs button[aria-current]{font-weight:600;cursor:default;border-color:transparent}
.sv-explorer .sv-crumbs .sep,.sv-explorer .sv-hint{opacity:.6}
.sv-explorer .sv-hint{font-size:12px;margin-right:6px}
.sv-explorer .sv-level{min-width:4.5ch;text-align:center;font-variant-numeric:tabular-nums}
.sv-explorer .sv-stage{overflow:hidden;position:relative}
.sv-explorer .sv-stage.zoomed{cursor:grab}
.sv-explorer .sv-stage.dragging{cursor:grabbing}
.sv-explorer .sv-canvas{transform-origin:0 0}
.sv-explorer .sv-canvas svg{display:block;width:100%;height:auto;margin:0 auto}
.sv-explorer .sv-go{cursor:pointer}
.sv-explorer .sv-stage.dragging .sv-go{cursor:grabbing}
.sv-explorer .sv-go:hover>path,.sv-explorer .sv-go:hover>polygon,.sv-explorer .sv-go:focus>path,.sv-explorer .sv-go:focus>polygon{stroke-width:3.5}
.sv-explorer .sv-go:hover>text,.sv-explorer .sv-go:focus>text{text-decoration:underline}
.sv-explorer .sv-go:focus{outline:none}")

(def ^:private script
  "(function(root){
var data=JSON.parse(root.querySelector('script[type=\"application/json\"]').textContent);
var stage=root.querySelector('.sv-stage'),crumbs=root.querySelector('.sv-crumbs'),level=root.querySelector('.sv-level');
var stack=[data.start],canvas=null,z={s:1,x:0,y:0},MAX=8,drag=null,dragged=false;
function dark(){var t=document.documentElement.getAttribute('data-theme');if(t)return t==='dark';return window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches;}
function mine(){return (window.svExplorerLast||root)===root;}
function go(k){var i=stack.indexOf(k);if(i>=0)stack=stack.slice(0,i+1);else stack.push(k);show();}
// zoom: a scale and offset on the drawing. Zoomed, the view grows with it, to
// three quarters of the window, so a wide chart isn't seen through a slit
function apply(){
  if(canvas)canvas.style.transform=z.s===1?'':'translate('+z.x+'px,'+z.y+'px) scale('+z.s+')';
  level.textContent=Math.round(z.s*100)+'%';
  root.querySelector('.sv-out').disabled=root.querySelector('.sv-fit').disabled=z.s===1;
  root.querySelector('.sv-in').disabled=z.s>=MAX;
  stage.classList.toggle('zoomed',z.s>1);}
function clamp(){
  if(!canvas)return;
  var cw=canvas.offsetWidth,ch=canvas.offsetHeight;
  stage.style.height=z.s===1?'':Math.max(ch,Math.min(ch*z.s,window.innerHeight*0.75))+'px';
  z.x=Math.min(0,Math.max(stage.clientWidth-cw*z.s,z.x));z.y=Math.min(0,Math.max(stage.clientHeight-ch*z.s,z.y));}
function zoomAt(f,px,py){
  var s=Math.min(MAX,Math.max(1,z.s*f));if(s===z.s)return;
  z.x=px-(px-z.x)*s/z.s;z.y=py-(py-z.y)*s/z.s;z.s=s;clamp();apply();}
function zoomMid(f){zoomAt(f,stage.clientWidth/2,stage.clientHeight/2);}
function fit(){z={s:1,x:0,y:0};clamp();apply();}
function show(keep){
  var v=data.views[stack[stack.length-1]];
  stage.innerHTML='<div class=\"sv-canvas\">'+((dark()&&v.svg.dark)||v.svg.light)+'</div>';
  canvas=stage.firstChild;
  var svg=canvas.querySelector('svg');
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
  if(keep)apply();else fit();
}
stage.addEventListener('click',function(e){
  if(dragged){dragged=false;return;}
  var el=e.target.closest&&e.target.closest('[data-go]');if(el)go(el.getAttribute('data-go'));});
stage.addEventListener('keydown',function(e){var el=e.target.closest&&e.target.closest('[data-go]');if(el&&(e.key==='Enter'||e.key===' ')){e.preventDefault();go(el.getAttribute('data-go'));}});
// the wheel zooms at the pointer; at the fitted size, scrolling down scrolls the page instead
stage.addEventListener('wheel',function(e){
  var dy=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?400:1);
  if(dy>=0&&z.s<=1&&!e.ctrlKey)return;
  e.preventDefault();var r=stage.getBoundingClientRect();
  zoomAt(Math.exp(-dy*0.002),e.clientX-r.left,e.clientY-r.top);},{passive:false});
// a drag pans a zoomed drawing; the click that ends it opens nothing
stage.addEventListener('pointerdown',function(e){dragged=false;if(z.s>1&&e.button===0)drag={x:e.clientX,y:e.clientY,zx:z.x,zy:z.y,id:e.pointerId,on:false};});
stage.addEventListener('pointermove',function(e){
  if(!drag)return;var dx=e.clientX-drag.x,dy=e.clientY-drag.y;
  if(!drag.on){if(Math.abs(dx)+Math.abs(dy)<5)return;drag.on=dragged=true;stage.setPointerCapture(drag.id);stage.classList.add('dragging');}
  z.x=drag.zx+dx;z.y=drag.zy+dy;clamp();apply();});
function end(){drag=null;stage.classList.remove('dragging');}
stage.addEventListener('pointerup',end);stage.addEventListener('pointercancel',end);
root.querySelector('.sv-in').addEventListener('click',function(){zoomMid(1.5);});
root.querySelector('.sv-out').addEventListener('click',function(){zoomMid(1/1.5);});
root.querySelector('.sv-fit').addEventListener('click',fit);
// keys go to the explorer used last: a click replaces the SVG, so focus can't say which
root.addEventListener('pointerdown',function(){window.svExplorerLast=root;});
root.addEventListener('focusin',function(){window.svExplorerLast=root;});
document.addEventListener('keydown',function(e){
  if(!mine()||e.ctrlKey||e.metaKey||e.altKey||/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName))return;
  if(e.key==='Escape'&&stack.length>1){stack.pop();show();}
  else if(e.key==='+'||e.key==='=')zoomMid(1.5);
  else if(e.key==='-')zoomMid(1/1.5);
  else if(e.key==='0')fit();});
window.addEventListener('resize',function(){clamp();apply();});
if(window.matchMedia)matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(){show(true);});
new MutationObserver(function(){show(true);}).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
show();
})(document.currentScript.parentNode);")

(defn fragment
  "The explorer as an HTML fragment: a <div class=\"sv-explorer\"> with its
  style, data and script, to put in a page. Several can share a page."
  [chart opts]
  (str "<div class=\"sv-explorer\">\n"
       "<style>" style "</style>\n"
       "<div class=\"sv-bar\"><nav class=\"sv-crumbs\" aria-label=\"Where you are\"></nav>"
       "<div class=\"sv-zoom\"><span class=\"sv-hint\">click a box to open it · scroll to zoom · drag to pan · Esc goes back</span>"
       "<button type=\"button\" class=\"sv-out\" aria-label=\"Zoom out\">−</button><span class=\"sv-level\">100%</span>"
       "<button type=\"button\" class=\"sv-in\" aria-label=\"Zoom in\">+</button>"
       "<button type=\"button\" class=\"sv-fit\">Fit</button></div></div>\n"
       "<div class=\"sv-stage\"></div>\n"
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
