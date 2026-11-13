/*
 * Build History Enhancer - sidebar adapter.
 *
 * Enhances the native Jenkins "Builds" card in place. Three things are added:
 *
 *   1. an "only my builds" checkbox and an initiator-display control under the existing search box
 *   2. a metadata row showing who started each build, between the build row and its description
 *   3. server-side filtering, so a search covers all history rather than the loaded rows
 *
 * Design notes
 * ------------
 * Native history keeps working untouched while no filter is active: core's own builds-card.js owns
 * the card, refreshes it, and pages through it. We only decorate the rows it renders.
 *
 * Once a filter is switched on we take over fetching, using core's documented
 * `data-auto-refresh="false"` switch on #buildHistoryPage to stop its polling, and intercepting the
 * pagination buttons in the capture phase. Clearing the filter reverses both and hands the card
 * back. Core's load() checks that attribute at call time, so this works regardless of which script
 * ran first.
 *
 * Every DOM element we rely on is checked for before use. If the card is not the shape this version
 * of the plugin knows, we bail out and leave native history alone.
 */
(function () {
  "use strict";

  var ROOT_SELECTOR = ".bhe-root";
  var PAGE_ID = "buildHistoryPage";
  var REFRESH_MS = 5000;
  /** Marks a row as already decorated, so repeated passes are idempotent. */
  var DECORATED_ATTR = "data-bhe-decorated";

  function onReady(fn) {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", fn);
    } else {
      fn();
    }
  }

  onReady(function () {
    var root = document.querySelector(ROOT_SELECTOR);
    if (!root) {
      return;
    }

    var page = document.getElementById(PAGE_ID);
    var card = document.querySelector("#jenkins-builds");
    if (!page || !card) {
      // No build history card on this page, or a layout this plugin does not know.
      return;
    }

    var dom = {
      page: page,
      card: card,
      contents: card.querySelector("#jenkins-build-history"),
      container: card.querySelector(".app-builds-container"),
      search: page.querySelector(".jenkins-search"),
      // Core's own class first; the bare input is a fallback in case that class is renamed.
      searchInput: page.querySelector(".jenkins-search__input") || page.querySelector("input"),
      loading: card.querySelector("#loading-builds"),
      noBuilds: card.querySelector("#no-builds"),
      controlsRow: document.querySelector("#controls"),
      pagePrevious: document.querySelector("#up"),
      pageNext: document.querySelector("#down")
    };

    if (!dom.contents || !dom.container || !dom.search || !dom.searchInput) {
      // Degrade silently: native history remains fully functional.
      return;
    }

    var app = new Enhancer(root, dom);
    app.start();
  });

  /** @param {HTMLElement} root the hidden carrier element rendered by the widget
   * @param {object} dom resolved elements of the native builds card
   */
  function Enhancer(root, dom) {
    this.root = root;
    this.dom = dom;
    this.api = root.getAttribute("data-bhe-api") || "";
    this.authenticated = root.getAttribute("data-bhe-authenticated") === "true";
    this.mode = root.getAttribute("data-bhe-mode") || "AUTO";
    this.i18n = root.querySelector('[data-bhe-role="i18n"]');

    /** Build number -> initiator metadata, cached for the lifetime of the page. */
    this.initiators = {};
    /** Build numbers already requested, so a slow response is not re-requested. */
    this.pending = {};
    /** Stack of `before` cursors, so "newer" can walk back without a reverse scan. */
    this.cursors = [];
    /** Cursor for the page currently shown; null means the newest page. */
    this.cursor = null;
    this.nextCursor = null;
    this.hasMore = false;
    this.enhanced = false;
    this.timer = null;
    /** Guards against reacting to the synthetic input event used to wake native code. */
    this.suppressInput = false;
    this.nativeAutoRefresh = dom.page.dataset.autoRefresh;
    /** Key used to persist advanced filter state in localStorage. */
    this.storageKey = "bhe-filters:" + (this.api.replace(/^.*\/job\//, "job/").replace(/\/bhe\/$/, ""));
  }

  Enhancer.prototype.start = function () {
    this.installControls();
    this.installInterceptors();
    this.observeRows();
    // Decorate whatever native code has already rendered, and whatever it renders next.
    this.decorateAll();
  };

  /* ---------------------------------------------------------------- controls */

  /** Moves the checkbox and initiator control out of the hidden block, under the search box. */
  Enhancer.prototype.installControls = function () {
    var controls = this.root.querySelector('[data-bhe-role="controls"]');
    if (!controls) {
      return;
    }
    this.dom.search.insertAdjacentElement("afterend", controls);
    this.root.classList.remove("jenkins-hidden");
    this.root.classList.add("bhe-root--active");

    var self = this;

    this.mineBox = controls.querySelector('[data-bhe-role="mine"]');
    if (this.mineBox) {
      this.mineBox.addEventListener("change", function () {
        self.onFilterChanged();
      });
    }

    this.modeSelect = controls.querySelector('[data-bhe-role="initiator-mode"]');
    if (this.modeSelect) {
      this.modeSelect.value = this.mode;
      this.modeSelect.addEventListener("change", function () {
        self.mode = self.modeSelect.value;
        self.saveMode(self.mode);
        self.redecorate();
      });
    }

    this.dom.searchInput.addEventListener("input", function () {
      if (self.suppressInput) {
        return;
      }
      self.onFilterChanged();
    });

    this.installAdvancedFilters();
  };

  /**
   * Takes over the controls that would otherwise make core fetch unfiltered history.
   *
   * Listeners are attached in the capture phase on an ancestor, so they run before core's own
   * listeners on the buttons themselves, and only swallow the event while a filter is active.
   */
  Enhancer.prototype.installInterceptors = function () {
    var self = this;

    if (this.dom.controlsRow) {
      this.dom.controlsRow.addEventListener(
        "click",
        function (event) {
          if (!self.enhanced) {
            return; // Native paging, untouched.
          }
          var next = event.target.closest("#down");
          var previous = event.target.closest("#up");
          if (!next && !previous) {
            return;
          }
          event.preventDefault();
          event.stopPropagation();
          if (next) {
            self.goOlder();
          } else {
            self.goNewer();
          }
        },
        true
      );
    }

    // Jenkins' development mode adds a manual refresh button that forces a native load.
    var refresh = document.getElementById("refresh-history-widget");
    if (refresh) {
      refresh.addEventListener(
        "click",
        function (event) {
          if (!self.enhanced) {
            return;
          }
          event.preventDefault();
          event.stopPropagation();
          self.load();
        },
        true
      );
    }

    window.addEventListener("focus", function () {
      if (self.enhanced) {
        self.load();
      }
    });
  };

  /**
   * Wires up the advanced-filters toggle, panel, and Reset button.
   * Restores the last saved state from localStorage, then moves the panel element out of the
   * hidden .bhe-root carrier so it can be positioned beneath the controls row in the DOM.
   */
  Enhancer.prototype.installAdvancedFilters = function () {
    // installControls() already moved [data-bhe-role="controls"] out of root and placed it
    // right after the search box. Find it there, not in root.
    var controls = this.dom.search.nextElementSibling;
    if (!controls || !controls.getAttribute("data-bhe-role")) {
      return;
    }
    var toggleBtn = controls.querySelector('[data-bhe-role="toggle-advanced"]');
    var badge = controls.querySelector('[data-bhe-role="filter-badge"]');

    var advancedEl = this.root.querySelector('[data-bhe-role="advanced"]');
    if (!advancedEl || !toggleBtn) {
      return;
    }

    // Move the advanced section out of the hidden bhe-root and position it directly after
    // controls, so its absolutely-positioned panel floats over the build list when open.
    controls.parentNode.insertBefore(advancedEl, controls.nextSibling);
    advancedEl.classList.remove("jenkins-hidden");

    var panel = advancedEl.querySelector('[data-bhe-role="advanced-panel"]');
    var resetBtn = advancedEl.querySelector('[data-bhe-role="reset-filters"]');

    if (!panel) {
      return;
    }

    // Stash for methods called after setup (clearFilters, etc.)
    this._advancedEl = advancedEl;
    this._advancedBadge = badge;

    var self = this;

    // Restore saved state before wiring events so the initial badge is correct.
    this.restoreAdvancedFilters();
    this.updateFilterBadge(badge);

    // If filters were saved from a previous visit, apply them now. Without this,
    // navigating away and back would restore the visual checkbox state but leave the
    // initial load unfiltered — the restored filters only took effect on the next
    // user interaction.
    if (this.advancedFilterCount() > 0) {
      this.onFilterChanged();
    }

    toggleBtn.addEventListener("click", function () {
      var isOpen = toggleBtn.getAttribute("aria-expanded") === "true";
      toggleBtn.setAttribute("aria-expanded", isOpen ? "false" : "true");
      panel.classList.toggle("jenkins-hidden", isOpen);
    });

    // Any change inside the panel triggers a reload (debounced) instead of requiring Apply.
    panel.addEventListener("change", function (e) {
      // Show/hide date range when CUSTOM is selected
      if (e.target && e.target.getAttribute("data-bhe-filter") === "period") {
        var dateRange = panel.querySelector('[data-bhe-role="date-range"]');
        if (dateRange) {
          dateRange.classList.toggle("jenkins-hidden", e.target.value !== "CUSTOM");
        }
      }

      self.saveAdvancedFilters();
      self.updateFilterBadge(badge);
      self.cursor = null;
      self.cursors = [];
      self.onFilterChanged();
    });

    if (resetBtn) {
      resetBtn.addEventListener("click", function () {
        self.resetAdvancedFilters(panel);
        self.saveAdvancedFilters();
        self.updateFilterBadge(badge);
        self.cursor = null;
        self.cursors = [];
        self.onFilterChanged();
      });
    }
  };

  /**
   * Counts how many advanced filter dimensions are non-default.
   * A dimension is non-default when at least one option in a group is unchecked,
   * or when a non-default option is selected (period / keep).
   */
  Enhancer.prototype.advancedFilterCount = function () {
    var panel = this._advancedEl;
    if (!panel) { return 0; }
    var count = 0;

    var allStatus = panel.querySelectorAll('[data-bhe-filter="status"]');
    var anyStatusOff = Array.prototype.some.call(allStatus, function (el) { return !el.checked; });
    if (anyStatusOff) { count++; }

    var allTrigger = panel.querySelectorAll('[data-bhe-filter="trigger"]');
    var anyTriggerOff = Array.prototype.some.call(allTrigger, function (el) { return !el.checked; });
    if (anyTriggerOff) { count++; }

    var period = panel.querySelector('[data-bhe-filter="period"]');
    if (period && period.value !== "") {
      // For CUSTOM, only count if at least one date is filled
      if (period.value === "CUSTOM") {
        var fromDate = panel.querySelector('[data-bhe-filter="from-date"]');
        var toDate = panel.querySelector('[data-bhe-filter="to-date"]');
        if ((fromDate && fromDate.value) || (toDate && toDate.value)) {
          count++;
        }
      } else {
        count++;
      }
    }

    var keep = panel.querySelector('[data-bhe-filter="keep"]');
    if (keep && keep.checked) { count++; }

    return count;
  };

  Enhancer.prototype.updateFilterBadge = function (badge) {
    if (!badge) { return; }
    var n = this.advancedFilterCount();
    if (n > 0) {
      badge.textContent = String(n);
      badge.classList.remove("jenkins-hidden");
    } else {
      badge.classList.add("jenkins-hidden");
    }
  };

  /** Resets all advanced filter inputs to their default (all checked / "all time" / keep off). */
  Enhancer.prototype.resetAdvancedFilters = function (panel) {
    if (!panel) { return; }
    var checks = panel.querySelectorAll('[data-bhe-filter="status"], [data-bhe-filter="trigger"]');
    Array.prototype.forEach.call(checks, function (el) { el.checked = true; });

    var period = panel.querySelector('[data-bhe-filter="period"]');
    if (period) { period.value = ""; }

    var fromDate = panel.querySelector('[data-bhe-filter="from-date"]');
    if (fromDate) { fromDate.value = ""; }

    var toDate = panel.querySelector('[data-bhe-filter="to-date"]');
    if (toDate) { toDate.value = ""; }

    var dateRange = panel.querySelector('[data-bhe-role="date-range"]');
    if (dateRange) { dateRange.classList.add("jenkins-hidden"); }

    var keep = panel.querySelector('[data-bhe-filter="keep"]');
    if (keep) { keep.checked = false; }
  };

  /** Persists advanced filter state to localStorage. */
  Enhancer.prototype.saveAdvancedFilters = function () {
    try {
      var state = this.collectAdvancedParams();
      localStorage.setItem(this.storageKey, JSON.stringify(state));
    } catch (e) {
      // localStorage unavailable: not an error, filters are still applied for this session.
    }
  };

  /** Restores advanced filter state from localStorage. */
  Enhancer.prototype.restoreAdvancedFilters = function () {
    try {
      var raw = localStorage.getItem(this.storageKey);
      if (!raw) { return; }
      var state = JSON.parse(raw);
      var panel = this._advancedEl;
      if (!panel) { return; }

      if (state.statuses) {
        panel.querySelectorAll('[data-bhe-filter="status"]').forEach(function (el) {
          el.checked = state.statuses.indexOf(el.value) !== -1;
        });
      }
      if (state.triggers) {
        panel.querySelectorAll('[data-bhe-filter="trigger"]').forEach(function (el) {
          el.checked = state.triggers.indexOf(el.value) !== -1;
        });
      }
      var period = panel.querySelector('[data-bhe-filter="period"]');
      if (period && state.period !== undefined) { period.value = state.period; }

      var fromDate = panel.querySelector('[data-bhe-filter="from-date"]');
      if (fromDate && state.fromDate !== undefined) { fromDate.value = state.fromDate; }

      var toDate = panel.querySelector('[data-bhe-filter="to-date"]');
      if (toDate && state.toDate !== undefined) { toDate.value = state.toDate; }

      // Show date range if CUSTOM is selected
      var dateRange = panel.querySelector('[data-bhe-role="date-range"]');
      if (dateRange && period && period.value === "CUSTOM") {
        dateRange.classList.remove("jenkins-hidden");
      }

      var keep = panel.querySelector('[data-bhe-filter="keep"]');
      if (keep && state.keep !== undefined) { keep.checked = !!state.keep; }
    } catch (e) {
      // Corrupt state: ignore and leave defaults.
    }
  };

  /** Collects advanced filter values as a plain object (for persistence and query building). */
  Enhancer.prototype.collectAdvancedParams = function () {
    var panel = this._advancedEl;
    var empty = { statuses: [], triggers: [], period: "", fromDate: "", toDate: "", keep: false };
    if (!panel) { return empty; }

    var statuses = Array.prototype.map.call(
      panel.querySelectorAll('[data-bhe-filter="status"]'),
      function (el) { return el.checked ? el.value : null; }
    ).filter(Boolean);

    var triggers = Array.prototype.map.call(
      panel.querySelectorAll('[data-bhe-filter="trigger"]'),
      function (el) { return el.checked ? el.value : null; }
    ).filter(Boolean);

    var period = panel.querySelector('[data-bhe-filter="period"]');
    var fromDate = panel.querySelector('[data-bhe-filter="from-date"]');
    var toDate = panel.querySelector('[data-bhe-filter="to-date"]');
    var keep = panel.querySelector('[data-bhe-filter="keep"]');

    return {
      statuses: statuses,
      triggers: triggers,
      period: period ? period.value : "",
      fromDate: fromDate ? fromDate.value : "",
      toDate: toDate ? toDate.value : "",
      keep: keep ? keep.checked : false
    };
  };

  /* ------------------------------------------------------------------ state */

  Enhancer.prototype.isMine = function () {
    return !!(this.mineBox && this.mineBox.checked && this.authenticated);
  };

  Enhancer.prototype.searchTerm = function () {
    return this.dom.searchInput.value.trim();
  };

  /** A filter is active when the checkbox is on, the search box has text, or an advanced filter is active. */
  Enhancer.prototype.shouldEnhance = function () {
    return this.isMine() || this.searchTerm() !== "" || this.advancedFilterCount() > 0;
  };

  Enhancer.prototype.onFilterChanged = function () {
    var wanted = this.shouldEnhance();
    if (wanted) {
      this.cursor = null;
      this.cursors = [];
      this.enterEnhanced();
      this.load();
    } else if (this.enhanced) {
      this.leaveEnhanced();
    } else {
      // Native mode throughout; core's debounced search handles it. Only the metadata rows are
      // ours, and the mutation observer re-adds them after core replaces the rows.
      this.redecorate();
    }
  };

  /** Stops core's polling and starts ours. */
  Enhancer.prototype.enterEnhanced = function () {
    if (this.enhanced) {
      return;
    }
    this.enhanced = true;
    this.nativeAutoRefresh = this.dom.page.dataset.autoRefresh;
    // Documented switch read by core's load() on every call: it returns before fetching.
    this.dom.page.dataset.autoRefresh = "false";
    this.startTimer();
  };

  /** Hands the card back to core and triggers a native reload. */
  Enhancer.prototype.leaveEnhanced = function () {
    if (!this.enhanced) {
      return;
    }
    this.enhanced = false;
    this.stopTimer();
    this.dom.page.dataset.autoRefresh = this.nativeAutoRefresh === "false" ? "false" : "true";
    // Make core treat the next load as a first page rather than resuming our cursor.
    this.dom.page.dataset.pageHasUp = "false";
    this.removeEmptyState();

    // Core loads on input; a synthetic event is the supported way to ask for that without
    // reaching into its internals. The flag stops our own handler from looping.
    this.suppressInput = true;
    try {
      this.dom.searchInput.dispatchEvent(new Event("input", { bubbles: true }));
    } finally {
      this.suppressInput = false;
    }
  };

  Enhancer.prototype.startTimer = function () {
    var self = this;
    this.stopTimer();
    this.timer = window.setTimeout(function () {
      self.load();
    }, REFRESH_MS);
  };

  Enhancer.prototype.stopTimer = function () {
    if (this.timer) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
  };

  /* ------------------------------------------------------------------ paging */

  Enhancer.prototype.goOlder = function () {
    if (!this.hasMore || this.nextCursor === null) {
      return;
    }
    this.cursors.push(this.cursor);
    this.cursor = this.nextCursor;
    this.load();
  };

  Enhancer.prototype.goNewer = function () {
    if (!this.cursors.length) {
      return;
    }
    this.cursor = this.cursors.pop();
    this.load();
  };

  /* ------------------------------------------------------------------ loading */

  /** Fetches and renders the current page of filtered history. */
  Enhancer.prototype.load = function () {
    if (!this.enhanced) {
      return;
    }
    this.stopTimer();
    if (document.hidden) {
      // Match core's behaviour: do not poll a hidden tab. Reschedule so it resumes on return.
      this.startTimer();
      return;
    }

    var self = this;
    var params = new URLSearchParams();
    params.set("search", this.searchTerm());
    params.set("mine", this.isMine() ? "true" : "false");
    if (this.cursor !== null) {
      params.set("before", String(this.cursor));
    }

    // Advanced filter params. A group is only sent when narrowed; absent means "all". An
    // emptied group is sent as "NONE" so that unchecking everything shows no builds rather than
    // all of them (the server treats an unknown name as selecting nothing).
    var adv = this.collectAdvancedParams();
    var panelEl = this._advancedEl;
    var allStatus = panelEl ? panelEl.querySelectorAll('[data-bhe-filter="status"]').length : 0;
    var allTrigger = panelEl ? panelEl.querySelectorAll('[data-bhe-filter="trigger"]').length : 0;
    if (adv.statuses.length < allStatus) {
      params.set("statuses", adv.statuses.length ? adv.statuses.join(",") : "NONE");
    }
    if (adv.triggers.length < allTrigger) {
      params.set("triggers", adv.triggers.length ? adv.triggers.join(",") : "NONE");
    }
    if (adv.period === "CUSTOM") {
      // datetime-local values are read in the browser's own time zone, then sent as epoch
      // millis so the server needs no time zone. "To" covers the whole of its minute.
      var fromMs = parseLocalDateTime(adv.fromDate);
      var toMs = parseLocalDateTime(adv.toDate);
      if (fromMs !== null) {
        params.set("from", String(fromMs));
      }
      if (toMs !== null) {
        params.set("to", String(toMs + 59999));
      }
    } else if (adv.period) {
      params.set("period", adv.period);
    }
    if (adv.keep) {
      params.set("keep", "true");
    }

    this.dom.container.classList.add("app-builds-container--loading");
    this.dom.search.classList.add("jenkins-search--loading");

    fetch(this.api + "history?" + params.toString(), {
      headers: { "Accept": "text/html" },
      credentials: "same-origin"
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("history request failed: " + response.status);
        }
        return response.text();
      })
      .then(function (html) {
        self.render(html);
        if (self.enhanced) {
          self.startTimer();
        }
      })
      .catch(function (error) {
        // Leave the rows that are on screen and hand the card back, so a server-side problem
        // degrades to working native history rather than an empty sidebar.
        if (window.console && window.console.warn) {
          window.console.warn("Build History Enhancer: falling back to native history", error);
        }
        self.clearLoading();
        self.leaveEnhanced();
      });
  };

  Enhancer.prototype.clearLoading = function () {
    this.dom.container.classList.remove("app-builds-container--loading");
    this.dom.search.classList.remove("jenkins-search--loading");
    if (this.dom.loading) {
      this.dom.loading.style.display = "none";
    }
  };

  /** Swaps in a rendered page and re-applies Jenkins behaviours to it. */
  Enhancer.prototype.render = function (html) {
    var holder = document.createElement("div");
    holder.innerHTML = html;
    var pageEl = holder.querySelector(".bhe-page");

    this.hasMore = !!(pageEl && pageEl.getAttribute("data-bhe-has-more") === "true");
    var cursorAttr = pageEl ? pageEl.getAttribute("data-bhe-next-cursor") : "";
    this.nextCursor = cursorAttr ? parseInt(cursorAttr, 10) : null;
    if (isNaN(this.nextCursor)) {
      this.nextCursor = null;
    }
    var empty = !!(pageEl && pageEl.getAttribute("data-bhe-empty") === "true");
    var truncated = !!(pageEl && pageEl.getAttribute("data-bhe-truncated") === "true");
    var scanned = pageEl ? pageEl.getAttribute("data-bhe-scanned") : "0";

    this.removeEmptyState();
    this.dom.contents.innerHTML = pageEl ? pageEl.innerHTML : "";
    if (this.dom.noBuilds) {
      this.dom.noBuilds.style.display = "none";
    }
    this.clearLoading();

    // Wires up tooltips, the row jumplist, cancel buttons and progress bars on the new rows.
    if (window.Behaviour && typeof window.Behaviour.applySubtree === "function") {
      try {
        window.Behaviour.applySubtree(this.dom.contents);
      } catch (error) {
        // A behaviour failure must not stop the rows from being usable links.
      }
    }

    if (empty) {
      this.showEmptyState(truncated, scanned);
    }
    this.updatePagingControls();
    this.decorateAll();
  };

  Enhancer.prototype.updatePagingControls = function () {
    var hasUp = this.cursors.length > 0;
    if (this.dom.controlsRow) {
      this.dom.controlsRow.classList.toggle("jenkins-hidden", !hasUp && !this.hasMore);
    }
    if (this.dom.pagePrevious) {
      this.dom.pagePrevious.classList.toggle("app-builds-container__button--disabled", !hasUp);
    }
    if (this.dom.pageNext) {
      this.dom.pageNext.classList.toggle("app-builds-container__button--disabled", !this.hasMore);
    }
  };

  /* -------------------------------------------------------------- empty state */

  Enhancer.prototype.showEmptyState = function (truncated, scanned) {
    var template = this.root.querySelector('[data-bhe-role="empty-template"]');
    if (!template || !template.content) {
      return;
    }
    var node = template.content.firstElementChild.cloneNode(true);
    var text = node.querySelector('[data-bhe-role="empty-text"]');
    if (text) {
      if (truncated) {
        var pattern = this.message("emptyScanned") || "{0}";
        text.textContent = pattern.replace("{0}", scanned);
      } else {
        text.textContent = this.message("emptyFiltered") || "";
      }
    }

    var self = this;
    var clear = node.querySelector('[data-bhe-role="clear"]');
    if (clear) {
      clear.addEventListener("click", function () {
        self.clearFilters();
      });
    }

    var older = node.querySelector('[data-bhe-role="search-older"]');
    if (older) {
      if (truncated && this.hasMore) {
        older.classList.remove("jenkins-hidden");
        older.addEventListener("click", function () {
          self.goOlder();
        });
      }
    }

    node.setAttribute("data-bhe-role", "empty");
    this.dom.contents.appendChild(node);
  };

  Enhancer.prototype.removeEmptyState = function () {
    var existing = this.dom.contents.querySelector('[data-bhe-role="empty"]');
    if (existing) {
      existing.remove();
    }
  };

  /** Resets every filter, advanced ones included, and returns the card to core. */
  Enhancer.prototype.clearFilters = function () {
    if (this.mineBox) {
      this.mineBox.checked = false;
    }
    this.resetAdvancedFilters(this._advancedEl);
    this.saveAdvancedFilters();
    this.updateFilterBadge(this._advancedBadge);
    this.dom.searchInput.value = "";
    this.cursor = null;
    this.cursors = [];
    this.leaveEnhanced();
  };

  /* ------------------------------------------------------------- decoration */

  /**
   * Watches for rows being replaced.
   *
   * Core replaces the whole row list on every refresh, which drops our metadata rows. The observer
   * puts them back. Records whose added nodes are all ours are ignored, so our own insertions
   * cannot trigger another pass.
   */
  Enhancer.prototype.observeRows = function () {
    var self = this;
    this.observer = new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        if (self.isForeignChange(records[i])) {
          self.decorateAll();
          return;
        }
      }
    });
    this.observer.observe(this.dom.contents, { childList: true, subtree: true });
  };

  /** True when a mutation added something other than our own metadata markup. */
  Enhancer.prototype.isForeignChange = function (record) {
    if (!record.addedNodes || !record.addedNodes.length) {
      return false;
    }
    for (var i = 0; i < record.addedNodes.length; i++) {
      var node = record.addedNodes[i];
      if (node.nodeType !== 1) {
        continue;
      }
      if (node.classList && node.classList.contains("bhe-meta")) {
        continue;
      }
      if (node.closest && node.closest(".bhe-meta")) {
        continue;
      }
      return true;
    }
    return false;
  };

  /** Whether metadata rows should be shown at all right now. */
  Enhancer.prototype.shouldShowMeta = function () {
    if (this.mode === "NEVER") {
      return false;
    }
    if (this.mode === "ALWAYS") {
      return true;
    }
    // AUTO: the identity is already known while "only my builds" is on, so the row adds nothing.
    // A user: search keeps it, since the point of that search is to look at someone else's builds.
    if (this.isMine() && !/^user:/i.test(this.searchTerm())) {
      return false;
    }
    return true;
  };

  Enhancer.prototype.redecorate = function () {
    var existing = this.dom.contents.querySelectorAll(".bhe-meta");
    for (var i = 0; i < existing.length; i++) {
      existing[i].remove();
    }
    var rows = this.dom.contents.querySelectorAll("[" + DECORATED_ATTR + "]");
    for (var j = 0; j < rows.length; j++) {
      rows[j].removeAttribute(DECORATED_ATTR);
    }
    this.decorateAll();
  };

  /** Adds a metadata row to every build row that does not have one yet. */
  Enhancer.prototype.decorateAll = function () {
    if (!this.shouldShowMeta()) {
      return;
    }
    var rows = this.dom.contents.querySelectorAll(".app-builds-container__item");
    var missing = [];

    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (row.hasAttribute(DECORATED_ATTR)) {
        continue;
      }
      var number = buildNumberOf(row);
      if (number === null) {
        // A queued item, or a row shape we do not recognise. Mark it so it is not retried.
        row.setAttribute(DECORATED_ATTR, "skip");
        continue;
      }
      var data = this.initiators[number];
      if (data) {
        this.insertMeta(row, data);
      } else if (!this.pending[number]) {
        missing.push(number);
      }
    }

    if (missing.length) {
      this.fetchInitiators(missing);
    }
  };

  Enhancer.prototype.fetchInitiators = function (numbers) {
    var self = this;
    for (var i = 0; i < numbers.length; i++) {
      this.pending[numbers[i]] = true;
    }

    fetch(this.api + "initiators?builds=" + numbers.join(","), {
      headers: { "Accept": "application/json" },
      credentials: "same-origin"
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("initiators request failed: " + response.status);
        }
        return response.json();
      })
      .then(function (payload) {
        var builds = (payload && payload.builds) || [];
        for (var i = 0; i < builds.length; i++) {
          self.initiators[builds[i].number] = builds[i];
        }
        for (var j = 0; j < numbers.length; j++) {
          delete self.pending[numbers[j]];
        }
        self.decorateAll();
      })
      .catch(function () {
        // No metadata this time. The rows stay exactly as Jenkins rendered them.
        for (var i = 0; i < numbers.length; i++) {
          delete self.pending[numbers[i]];
        }
      });
  };

  /**
   * Inserts one metadata row.
   *
   * It goes before the description when there is one, so the order is: build row, initiator,
   * description. All text is set with textContent, never innerHTML, so a build name or a cause
   * description from another plugin cannot inject markup.
   */
  Enhancer.prototype.insertMeta = function (row, data) {
    if (row.querySelector(".bhe-meta")) {
      row.setAttribute(DECORATED_ATTR, "1");
      return;
    }
    var template = this.root.querySelector('[data-bhe-role="meta-template"]');
    if (!template || !template.content) {
      return;
    }
    var meta = template.content.firstElementChild.cloneNode(true);

    var icon = meta.querySelector('[data-bhe-role="icon"]');
    var source = this.root.querySelector('[data-bhe-role="icon-' + cssSafe(data.type) + '"]');
    if (icon && source && source.firstElementChild) {
      icon.appendChild(source.firstElementChild.cloneNode(true));
    }

    var name = meta.querySelector('[data-bhe-role="name"]');
    if (name) {
      name.textContent = data.displayName || "";
      if (data.userId) {
        name.title = data.userId;
      }
    }

    if (data.reuseUrl) {
      meta.appendChild(this.reuseLink(data.reuseUrl));
    }

    // The row is a three-column grid: status icon, build, row-menu chevron. The line spans the first
    // two columns, so it has to come after the chevron in the DOM; placed before it, the grid would
    // push the chevron down onto the line's row. It goes above the description when there is one.
    var description = row.querySelector(".app-builds-container__item__description");
    if (description) {
      row.insertBefore(meta, description);
    } else {
      row.appendChild(meta);
    }
    row.setAttribute(DECORATED_ATTR, "1");
  };

  /**
   * The "reuse parameters" control.
   *
   * A link, not a button, so it is keyboard reachable and middle-clickable like the rest of the
   * row. The target always opens an editable form; the server refuses to hand out a URL that would
   * schedule a build directly.
   */
  Enhancer.prototype.reuseLink = function (url) {
    var link = document.createElement("a");
    link.className = "bhe-meta__reuse";
    link.href = url;
    var tooltip = this.message("reuseTooltip") || "";
    link.setAttribute("tooltip", tooltip);
    link.setAttribute("aria-label", tooltip);

    var source = this.root.querySelector('[data-bhe-role="icon-reuse"]');
    if (source && source.firstElementChild) {
      link.appendChild(source.firstElementChild.cloneNode(true));
    } else {
      link.textContent = "↻";
    }
    return link;
  };

  /* -------------------------------------------------------------- utilities */

  Enhancer.prototype.message = function (key) {
    if (!this.i18n) {
      return null;
    }
    var map = {
      reuseTooltip: "data-reuse-tooltip",
      emptyFiltered: "data-empty-filtered",
      emptyScanned: "data-empty-scanned"
    };
    return this.i18n.getAttribute(map[key]);
  };

  /** Persists the initiator preference. POST with Jenkins' CSRF token. */
  Enhancer.prototype.saveMode = function (mode) {
    var headers = { "Content-Type": "application/x-www-form-urlencoded" };
    if (window.crumb && typeof window.crumb.wrap === "function") {
      // Jenkins' own helper adds the crumb field name and value.
      var wrapped = window.crumb.wrap({});
      Object.keys(wrapped).forEach(function (key) {
        headers[key] = wrapped[key];
      });
    }
    fetch(this.api + "initiatorMode", {
      method: "POST",
      headers: headers,
      credentials: "same-origin",
      body: "mode=" + encodeURIComponent(mode)
    }).catch(function () {
      // The preference is cosmetic; a failure to persist it is not worth interrupting the user.
    });
  };

  /**
   * Build number for a row, or null when it is not a build row.
   *
   * The row's href is the primary source: it ends in the build number, and unlike the entry id it
   * stays within the range JavaScript numbers represent exactly.
   *
   * `page-entry-id` is the fallback. Core (HistoryPageEntry.getEntryId) encodes a completed build
   * as `Long.MIN_VALUE + buildNumber` — a large negative value — and a queued item as its positive
   * queue id, so the sign test is the opposite way round from what the name suggests. Those build
   * ids exceed Number.MAX_SAFE_INTEGER, so the offset is undone with BigInt where available.
   */
  var LONG_MIN = typeof BigInt === "function" ? BigInt("-9223372036854775808") : null;

  function buildNumberOf(row) {
    var link = row.querySelector(".app-builds-container__item__inner__link");
    if (link && link.getAttribute("href")) {
      var match = link.getAttribute("href").match(/\/(\d+)\/?$/);
      if (match) {
        return parseInt(match[1], 10);
      }
    }

    var raw = row.getAttribute("page-entry-id");
    if (raw) {
      // The sign is tested on the string, so this works without BigInt and keeps the file free of
      // BigInt literals, which would be a parse error on a browser that lacks them.
      if (raw.charAt(0) !== "-") {
        return null; // A queue id: a pending build, which has no number yet.
      }
      if (LONG_MIN !== null) {
        try {
          return Number(BigInt(raw) - LONG_MIN);
        } catch (error) {
          // Not an integer we can read; fall through.
        }
      }
    }
    return null;
  }

  /** Keeps an attribute selector safe even if the server ever sends an unexpected type. */
  function cssSafe(value) {
    return String(value || "UNKNOWN").replace(/[^A-Za-z_]/g, "");
  }

  /** Parses an HTML5 datetime-local value as a Date, returning its epoch millis or null. */
  function parseLocalDateTime(value) {
    if (!value) { return null; }
    // datetime-local format: "YYYY-MM-DDTHH:mm" or "YYYY-MM-DDTHH:mm:ss"
    var date = new Date(value);
    return !isNaN(date.getTime()) ? date.getTime() : null;
  }
})();
