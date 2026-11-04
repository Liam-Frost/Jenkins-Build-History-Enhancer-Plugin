# Contributing

Thanks for helping improve Build History Enhancer. General Jenkins plugin guidance is in the
[Jenkins contribution guide](https://www.jenkins.io/participate/code/); this file covers what is
specific to this plugin.

## Build and test

You need JDK 17, 21 or 25 and Maven 3.9 or newer.

```bash
mvn verify
```

This compiles, runs the tests, and runs the static checks used by ci.jenkins.io (SpotBugs, the
Jenkins-specific checks, and Spotless formatting). If formatting fails, fix it with:

```bash
mvn spotless:apply
```

To run one test class:

```bash
mvn test -Dtest=BuildHistoryApiTest
```

To try the plugin in a local Jenkins:

```bash
mvn hpi:run -Dport=8090
```

Then open http://localhost:8090/jenkins/. The `work/` directory it creates is the Jenkins home and is
ignored by git.

## What the tests cover

| Class | Covers |
| --- | --- |
| `SearchQueryTest` | parsing the search field, the `user:` prefix, length limits |
| `BuildInitiatorResolverTest` | mapping build causes to who started the build |
| `BuildSearchFilterTest` | matching one build against the filters, user ID case rules |
| `BuildHistoryServiceTest` | cursor paging, the scan limit, stable pages as new builds arrive |
| `BuildHistoryApiTest` | the HTTP endpoints, permissions, and that rows use core's own markup |

`sidebar.js` has no automated tests. Any change to it, or to the Jelly views it reads, needs a
manual check in a browser: the filter, search, the initiator line, paging, and the light and dark
themes.

## How it fits together

```
HistorySidebarWidget     WidgetFactory<Job>: adds sidebar.js and sidebar.css to job pages,
  index.jelly            plus hidden templates for the controls and the initiator line

BuildHistoryApi          TransientActionFactory<Job>: mounts <job>/bhe/
  GET  history           one page of filtered builds, as HTML
  GET  initiators        who started each requested build, as JSON
  POST initiatorMode     saves the viewer's display preference

BuildHistoryService      walks the job's builds newest first and collects one page of matches
BuildSearchFilter        decides whether one build matches
BuildInitiatorResolver   turns a build's causes into "who started it"
RebuildLinkService       finds Rebuilder's form URL, by reflection
FilteredHistoryWidget    feeds the matched builds through core's own history widget
```

While no filter is active, core's `builds-card.js` runs the card as usual and the plugin only adds
the initiator lines. When a filter is switched on, the script sets `data-auto-refresh="false"` on
the card, which core checks before each refresh, and fetches pages from `bhe/history` instead.
Clearing the filter hands the card back.

## Things that are easy to get wrong

These all caused real bugs during development.

**Rows must come from core.** `history.jelly` hands a `HistoryPageFilter` to core's own
`entries.jelly`, so rows match the stock sidebar. Two details make that work:

- The view is wrapped in `<l:ajax>`, as core's `ajaxBuildHistory.jelly` is. It sets up the page
  variables a full page gets from `l:layout`. Without it, status icons render as an empty `<img>`.
- `FilteredHistoryWidget` sets `filter.widget` itself. `BuildHistoryWidget.getHistoryPageFilter()`
  leaves it unset (core's `doAjax` fills it in afterwards), and `entry.jelly` needs it for the row
  links.

**`empty` is a JEXL keyword.** `${x.empty}` does not parse in Jelly, which is why the model exposes
`isNoResults()` rather than `isEmpty()`.

**`page-entry-id` is negative for builds.** Core encodes a build as `Long.MIN_VALUE + number` and a
queued item as its positive queue ID. The script reads the build number from the row link first.

**Checkbox labels.** Core's checkbox styling expects `<label class="attach-previous">` with no `for`
attribute. Adding `for` breaks toggling, and a `for` label alone leaves a focus ring after a click.

**User IDs are compared with the realm's `IdStrategy`**, never `equals`. Jenkins' default realm and
LDAP ignore case.

## Translations

The plugin ships English and Simplified Chinese. Every `.properties` bundle has an English base file
and a `_zh` translation, plus `index_zh.jelly` for the plugin description.

- Name translations `_zh`, never `_zh_CN`. Lookup goes `zh_TW` → `zh` → base, so `_zh` serves every
  Chinese locale, while a `_zh_CN` file would leave Taiwan and Hong Kong users on English.
- Add each new key to both files. `LocalizationTest` fails if the keys, or the `{0}` placeholders,
  differ between the two.
- Put user-facing text in the bundles only. `sidebar.js` reads its strings from attributes that
  `index.jelly` renders on the server, so the script contains no text of its own.
- A request without `Accept-Language` gets the server's language. That is Jetty's behaviour and
  matches the rest of Jenkins; do not work around it in the plugin.

## Security rules

Every change has to keep these true. Pull requests that touch them should say how.

- The current user comes from the security context. Never take a user ID from the request to decide
  what is "mine".
- Every endpoint checks `Item.READ` on the job, and each build is checked again while scanning.
  Builds the caller cannot read are left out silently, not reported.
- Build parameter values are never searched and never returned.
- Text from builds, users or other plugins is inserted with `textContent`, never as HTML.
- Endpoints that change state use `@RequirePOST` and the CSRF crumb.
- URLs sent to the browser are root-relative, never built from the request's `Host` header.
- `User.getById(id, false)`: looking up a user must not create one.

## Releases

Releases are automated (JEP-229). Merging a pull request to `main` with a label such as `bug` or
`enhancement` publishes a new version; release notes are drafted from the pull request titles.
