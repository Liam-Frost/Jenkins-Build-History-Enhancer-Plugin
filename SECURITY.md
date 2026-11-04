# Security policy

Please do not report security issues in this plugin as public GitHub issues.

Report them to the Jenkins security team instead, following
[the Jenkins security reporting process](https://www.jenkins.io/security/reporting/).
The team coordinates the fix and the advisory with the maintainers.

Areas where a report is especially welcome:

- "Only my builds" showing builds that belong to someone else, or hiding your own
- any endpoint under `<job>/bhe/` returning data about a job or build the caller cannot read
- build parameter values reaching the browser through this plugin
- markup from build names, descriptions or user names being interpreted as HTML
