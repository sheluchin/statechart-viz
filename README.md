# statechart-viz

Draws any [Fulcro statechart](https://github.com/fulcrologic/statecharts) as a Graphviz diagram,
with the active states highlighted, and zooms into any region of it. Hand it a compiled chart and,
optionally, a running session's configuration; it knows nothing about any particular chart.

Built as the shared viz layer for agent-pi's statechart extensions, so each extension gets viz
without writing its own renderer. Runs under babashka 1.13.223+ and on the JVM.

## Install

A git dependency in `bb.edn` or `deps.edn`:

```clojure
{:deps {io.github.sheluchin/statechart-viz {:git/sha "<commit sha>"}}}
```

Rendering to PNG or SVG needs the Graphviz `dot` binary on PATH. `dot` text output does not.

Use a recent Graphviz. Graphviz 2.43, the version in Ubuntu's apt repositories, lays sibling
clusters out right to left, so a parallel state's regions appear in reverse order. Graphviz 12.2
(for example the WASM build in `@viz-js/viz`) keeps them in chart order. The DOT is the same
either way.

```clojure
(require '[statechart-viz.core :as viz])

(viz/dot chart)                                   ; the whole chart, before it runs
(viz/dot chart {:active (rt/current-configuration env session-id)})   ; a running session
(viz/dot chart {:focus :intake/handling :depth 1})                     ; zoomed in
(viz/render chart {:active cfg :format :png})     ; => {:ok bytes} or {:error msg}
```

## API

| Function | Returns |
|---|---|
| `(dot chart opts)` | Graphviz DOT text |
| `(render chart opts)` | `{:ok bytes}` or `{:error message}`, using the `dot` binary; adds `:format` (`:png`, `:svg`) and `:dpi` |
| `(outline chart active)` | The state tree as data: `{:id :label :kind :active? :invokes :children}` |
| `(zoom-targets chart)` | Every container you can zoom into, outermost first, with its `:path` of labels. For a nav menu |

Options for `dot` and `render`:

| Key | Meaning |
|---|---|
| `:active` | Set of active state ids, i.e. a session's configuration |
| `:focus` | Draw only this state's subtree. Transitions leaving it end at a grey `↗ name` stub, and the title becomes a breadcrumb |
| `:depth` | Expand this many container levels below the focus; deeper ones become one `⊞ n states` box |
| `:title` | Title above the diagram |
| `:theme` | Partial map overriding `statechart-viz.theme/default` |

## How a chart is drawn

| Element | Drawn as |
|---|---|
| Compound state | Rounded box holding its children |
| Parallel state | Dashed box marked `∥`; its regions side by side |
| Final state | Double border |
| History | `H` or `H*` circle, dotted arrow to its default |
| Invoke | Blue 3D box inside the state, named after the child chart |
| Initial | Dot inside its region, one arrow to the first state |
| Guarded transition | Purple, marked `[guard]` or `[`*`:diagram/condition`*`]` |
| Targetless (internal) transition | Dotted self-loop, or `↺ event` in the label of a container |
| Transition between a container and its own child | Starts or ends at a small circle at the container's top |
| Active state | Gold fill, or a gold border on a container |

Labels default to the last segment of the id: `:round1.heads.round2` reads `round2` inside `heads`.
A state with no `:id` reads as its type (`parallel`), not the id the statecharts library generates
for it (see [Annotating a chart](#annotating-a-chart)). A generated id is a bare keyword of the type
plus digits, such as `:parallel29883`, so an explicit id of that exact shape is labelled the same way.
Platform events keep their full name (`done.state.decide`).

## Annotating a chart

A diagram is only as clear as the chart's ids and annotations, and those are up to the chart's
author. The library draws what it can without them: a label from the id, `[guard]` for a guard,
and a state's type when it has no `:id`. Annotate the chart to do better.

The first keys are the statecharts library's own diagram conventions (`chart/diagram-label`,
`chart/transition-label`), so one set of annotations serves its visualizer and this one:

```clojure
(parallel {:id :lights :diagram/label "Traffic lights"} ...)              ; instead of "parallel"
(transition {:event :open :cond unlocked? :target :door/open
             :diagram/condition "unlocked?"}                              ; open [unlocked?] / chime
  (script {:expr ring! :diagram/label "chime"}))
(state {:id :gate/reply :diagram/kind :success})                          ; green fill
```

| Key | On | Effect |
|---|---|---|
| `:id` | any state | The label, by its last segment. Give every state one: an unnamed state is labelled by its type, and the statecharts library's own tools show a generated id like `:parallel29883` |
| `:diagram/label` | state, invoke | The label |
| `:diagram/label` | transition | The whole edge text |
| `:diagram/condition` | transition | What the guard checks, shown as `[text]`. Guards are functions, so without it the edge only says `[guard]` |
| `:diagram/label` | executable content in a transition | An action, shown as `/ text` |
| `:diagram/kind` | state | This library only: a fill colour from the theme's `:kinds`: `:success`, `:failure`, `:decision`, `:waiting`, `:neutral`. Add your own with `{:theme {:kinds {:mine "#hex"}}}` |

## Zooming into invoked charts

An invoked chart is a separate session with its own chart and configuration. Draw it with its own
`dot` call, and pass a `:title` that says where it came from:

```clojure
(viz/dot coin/chart {:active child-cfg :title "game › Playing › ⤵ coin"})
```

## Develop

```bash
bb test      # needs Graphviz `dot` for the render tests; they are skipped without it
bb gallery   # renders every test chart to out/*.png
```
