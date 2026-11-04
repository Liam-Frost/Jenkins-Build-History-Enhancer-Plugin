# Build History Enhancer

[![Build](https://github.com/Liam-Frost/Jenkins-Build-History-Enhancer-Plugin/actions/workflows/build.yml/badge.svg)](https://github.com/Liam-Frost/Jenkins-Build-History-Enhancer-Plugin/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-4d6270)](LICENSE.md)

English · [简体中文](README.zh-CN.md)

Adds four things to the build history sidebar on every job page:

- an **"Only my builds"** filter
- a **search** that covers the whole history, not just the builds already loaded
- a line under each build showing **who or what started it**
- a **reuse parameters** shortcut that opens a prefilled build form

It extends the existing Builds card instead of replacing it. Rows are still drawn by Jenkins
itself, so status icons, badges, progress bars, the cancel button, the row menu and the dark theme
look and behave as usual.

<p align="center">
  <img src="docs/images/sidebar.png" alt="The Builds card with the Only my builds filter, the Started by selector, and a line under each build naming who started it" width="330">
</p>

## Getting started

Install the plugin and open any job. The controls appear under the search field of the Builds
card. No configuration is needed.

For the reuse parameters shortcut, also install the [Rebuilder](https://plugins.jenkins.io/rebuild/)
plugin.

### Installing

Once the plugin is in the Jenkins update center, install it from **Manage Jenkins → Plugins →
Available plugins**.

Until then, download `build-history-enhancer-<version>.hpi` from the
[GitHub releases](https://github.com/Liam-Frost/Jenkins-Build-History-Enhancer-Plugin/releases),
then upload it under **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin** and restart
Jenkins when asked.

## Features

### Only my builds

Ticking **Only my builds** keeps the builds you started and hides the rest. Builds started by a
timer, an SCM change, an upstream job or an anonymous user are never counted as yours.

The checkbox is disabled when you are not logged in.

<p align="center">
  <img src="docs/images/only-mine.png" alt="Only my builds ticked: build #5, started by an upstream job, is gone, and the Started by line is hidden because every build shown is yours" width="330">
</p>

### Search

The search field matches, across all of the job's history:

- the build number
- the build's display name
- the build's description
- the user ID and full name of whoever started it

To search only by who started the build, type `user:` followed by an ID or name, for example
`user:alex`. That finds builds Alex started, but not builds that merely mention Alex in their
description.

The search and **Only my builds** combine: with the box ticked, `user:bob` shows nothing, because
none of your builds were started by Bob.

Build parameter values are not searched, so that the contents of parameters cannot be discovered
through the search.

<p align="center">
  <img src="docs/images/search.png" alt="Searching for QA finds build #4 through its description" width="330">
</p>

### Who started the build

A line under each build shows who or what started it:

| Started by | Shown as |
| --- | --- |
| a user | the user's full name |
| a timer | Started by timer |
| an SCM change | Started by SCM change |
| an upstream job | Started by *upstream-job* #*number* |
| an anonymous user | Started anonymously |
| something else, such as another plugin | that trigger's own description, for example *Started by GitHub push* |

Use the selector next to the checkbox to choose when this line appears. The choice is saved for your
user account.

- **Auto** (default) hides it while **Only my builds** is ticked, since every build is then yours.
- **Always** shows it.
- **Never** hides it.

### Reuse parameters

With Rebuilder installed, builds that ran with parameters get a reuse icon next to the name of who
started them. It opens Rebuilder's form, filled in with that build's values, so you can change
anything before you click **Rebuild**. Clicking the icon never starts a build by itself.

The icon does not appear when:

- the build ran without parameters
- you are not allowed to build the job
- Rebuilder is set to rebuild immediately, without showing a form

## Configuration

**Manage Jenkins → System → Build History Enhancer**

| Setting | Default | What it does |
| --- | --- | --- |
| Enhance build history sidebar | on | Turns the plugin off without uninstalling it. The sidebar is then exactly as stock Jenkins. |
| Builds per filtered page | 30 | How many matching builds one page shows (5–100). |
| Maximum builds examined per search | 2000 | Limits how far back one search reads (50–50000). |

On a job with a very long history, a search may reach the limit before it finds a match. The sidebar
then says how many builds it checked and offers to continue into older builds.

The settings can also be managed with [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/):

```yaml
unclassified:
  buildHistoryEnhancer:
    enabled: true
    pageSize: 30
    maxScan: 2000
```

## Languages

The plugin is available in English and Simplified Chinese, and uses the same language as the rest
of the Jenkins page:

- a browser set to any Chinese (mainland, Taiwan, Hong Kong, ...) gets Simplified Chinese
- a browser set to any other language gets English
- a client that sends no language at all, such as a script, gets the server's language, as Jenkins
  itself does

## Compatibility

- Freestyle projects, Pipelines and other job types, including jobs inside folders
- Light and dark themes

## Limitations

- Builds waiting in the queue are only listed on the first page, as in stock Jenkins.
- Searching a very long history may take several "continue" clicks to reach old builds.
- The plugin relies on the layout of the Builds card in current Jenkins versions. If a future Jenkins
  redesigns the card, the plugin's controls do not appear and the stock sidebar is left as it is.

## Reporting issues

Report bugs and feature requests in [GitHub issues](https://github.com/Liam-Frost/Jenkins-Build-History-Enhancer-Plugin/issues).
Report security issues to the Jenkins security team instead; see [SECURITY.md](SECURITY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE.md)
