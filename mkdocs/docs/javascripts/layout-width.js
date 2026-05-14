(function () {
  var optionSets = {
    width: {
      values: ["compact", "comfortable", "wide", "full"],
      labels: {
        compact: "紧凑",
        comfortable: "中等",
        wide: "宽屏",
        full: "最大"
      },
      titles: {
        compact: "compact",
        comfortable: "comfortable",
        wide: "wide",
        full: "full"
      },
      fallback: "compact"
    },
    spacing: {
      values: ["compact", "normal", "wide", "extra"],
      labels: {
        compact: "紧凑",
        normal: "常规",
        wide: "宽松",
        extra: "最大"
      },
      titles: {
        compact: "compact",
        normal: "normal",
        wide: "wide",
        extra: "extra"
      },
      fallback: "compact"
    }
  };
  var settings = [
    {
      key: "nav",
      label: "左侧目录",
      title: "左目录宽度",
      attribute: "data-docs-nav-width",
      storageKey: "java-learning-docs-nav-width",
      optionSet: "width"
    },
    {
      key: "content",
      label: "正文",
      title: "正文宽度",
      attribute: "data-docs-content-width",
      storageKey: "java-learning-docs-content-width",
      optionSet: "width"
    },
    {
      key: "toc",
      label: "右侧目录",
      title: "右目录宽度",
      attribute: "data-docs-toc-width",
      storageKey: "java-learning-docs-toc-width",
      optionSet: "width"
    },
    {
      key: "letter-spacing",
      label: "字距",
      title: "正文字距",
      attribute: "data-docs-letter-spacing",
      storageKey: "java-learning-docs-letter-spacing",
      optionSet: "spacing"
    }
  ];

  function getOptionSet(setting) {
    return optionSets[setting.optionSet] || optionSets.width;
  }

  function isAllowed(setting, value) {
    return getOptionSet(setting).values.indexOf(value) !== -1;
  }

  function getStoredValue(setting) {
    try {
      var stored = localStorage.getItem(setting.storageKey);
      if (isAllowed(setting, stored)) {
        return stored;
      }
      if (stored !== null) {
        localStorage.removeItem(setting.storageKey);
      }
    } catch (error) {}
    return null;
  }

  function getCurrentValue(setting) {
    var current = document.documentElement.getAttribute(setting.attribute);
    var options = getOptionSet(setting);
    return isAllowed(setting, current) ? current : options.fallback;
  }

  function saveValue(setting, value) {
    try {
      localStorage.setItem(setting.storageKey, value);
    } catch (error) {}
  }

  function syncButtons(setting, value) {
    var selector =
      "[data-docs-layout-setting-target='" + setting.key + "'] " +
      "[data-docs-layout-setting-value], " +
      "[data-docs-layout-width-target='" + setting.key + "'] " +
      "[data-docs-layout-width-value]";
    var buttons = document.querySelectorAll(selector);
    for (var i = 0; i < buttons.length; i += 1) {
      var button = buttons[i];
      var buttonValue =
        button.getAttribute("data-docs-layout-setting-value") ||
        button.getAttribute("data-docs-layout-width-value");
      var active = buttonValue === value;
      button.setAttribute("aria-pressed", active ? "true" : "false");
    }
  }

  function applyValue(setting, value, persist) {
    var options = getOptionSet(setting);
    if (!isAllowed(setting, value)) {
      value = options.fallback;
    }
    document.documentElement.setAttribute(setting.attribute, value);
    syncButtons(setting, value);
    if (persist) {
      saveValue(setting, value);
    }
  }

  function setPanelOpen(switcher, open) {
    var trigger = switcher.querySelector(".docs-layout-widths__trigger");
    var panel = switcher.querySelector(".docs-layout-widths__panel");
    if (!trigger || !panel) {
      return;
    }
    trigger.setAttribute("aria-expanded", open ? "true" : "false");
    panel.hidden = !open;
  }

  function closePanels(except) {
    var switchers = document.querySelectorAll("[data-md-component='docs-layout-widths']");
    for (var i = 0; i < switchers.length; i += 1) {
      if (switchers[i] !== except) {
        setPanelOpen(switchers[i], false);
      }
    }
  }

  function createSetting(setting) {
    var row = document.createElement("div");
    var optionsConfig = getOptionSet(setting);
    row.className = "docs-layout-widths__setting";
    row.setAttribute("data-docs-layout-width-target", setting.key);
    row.setAttribute("data-docs-layout-setting-target", setting.key);

    var label = document.createElement("span");
    label.className = "docs-layout-widths__label";
    label.textContent = setting.label;
    label.title = setting.title;
    row.appendChild(label);

    var options = document.createElement("div");
    options.className = "docs-layout-widths__options";
    options.setAttribute("role", "group");
    options.setAttribute("aria-label", setting.title);

    optionsConfig.values.forEach(function (value) {
      var button = document.createElement("button");
      button.className = "docs-layout-widths__button";
      button.type = "button";
      button.textContent = optionsConfig.labels[value];
      button.title = setting.title + "：" + optionsConfig.labels[value];
      button.setAttribute("aria-label", setting.title + "：" + optionsConfig.labels[value]);
      button.setAttribute("aria-pressed", "false");
      button.setAttribute("data-docs-layout-width-value", value);
      button.setAttribute("data-docs-layout-setting-value", value);
      button.addEventListener("click", function () {
        applyValue(setting, value, true);
      });
      options.appendChild(button);
    });

    row.appendChild(options);
    return row;
  }

  function createSwitcher() {
    var switcher = document.createElement("div");
    switcher.className = "docs-layout-widths";
    switcher.setAttribute("data-md-component", "docs-layout-widths");

    var trigger = document.createElement("button");
    trigger.className = "docs-layout-widths__trigger";
    trigger.type = "button";
    trigger.textContent = "布局";
    trigger.title = "调整文档布局";
    trigger.setAttribute("aria-label", "调整文档布局");
    trigger.setAttribute("aria-haspopup", "true");
    trigger.setAttribute("aria-expanded", "false");

    var panel = document.createElement("div");
    panel.className = "docs-layout-widths__panel";
    panel.hidden = true;
    panel.setAttribute("role", "group");
    panel.setAttribute("aria-label", "文档布局");

    trigger.addEventListener("click", function (event) {
      var open = trigger.getAttribute("aria-expanded") !== "true";
      event.stopPropagation();
      closePanels(switcher);
      setPanelOpen(switcher, open);
    });

    settings.forEach(function (setting) {
      panel.appendChild(createSetting(setting));
    });

    switcher.appendChild(trigger);
    switcher.appendChild(panel);
    return switcher;
  }

  function mountSwitcher() {
    var header = document.querySelector(".md-header__inner");
    if (!header) {
      return;
    }

    var switcher = header.querySelector("[data-md-component='docs-layout-widths']");
    if (!switcher) {
      switcher = createSwitcher();
      var search = header.querySelector("label[for='__search']");
      header.insertBefore(switcher, search || null);
    }

    settings.forEach(function (setting) {
      applyValue(setting, getStoredValue(setting) || getCurrentValue(setting), false);
    });
  }

  if (typeof document$ !== "undefined") {
    document$.subscribe(mountSwitcher);
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mountSwitcher);
  } else {
    mountSwitcher();
  }

  document.addEventListener("click", function (event) {
    var switcher = event.target.closest
      ? event.target.closest("[data-md-component='docs-layout-widths']")
      : null;
    if (!switcher) {
      closePanels();
    }
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      closePanels();
    }
  });
})();
