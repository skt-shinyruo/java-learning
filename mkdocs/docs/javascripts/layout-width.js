(function () {
  var values = ["compact", "comfortable", "wide", "full"];
  var valueLabels = {
    compact: "紧凑",
    comfortable: "中等",
    wide: "宽屏",
    full: "最大"
  };
  var valueTitles = {
    compact: "compact",
    comfortable: "comfortable",
    wide: "wide",
    full: "full"
  };
  var settings = [
    {
      key: "nav",
      label: "左侧目录",
      title: "左目录宽度",
      attribute: "data-docs-nav-width",
      storageKey: "java-learning-docs-nav-width"
    },
    {
      key: "content",
      label: "正文",
      title: "正文宽度",
      attribute: "data-docs-content-width",
      storageKey: "java-learning-docs-content-width"
    },
    {
      key: "toc",
      label: "右侧目录",
      title: "右目录宽度",
      attribute: "data-docs-toc-width",
      storageKey: "java-learning-docs-toc-width"
    }
  ];

  function isAllowed(value) {
    return values.indexOf(value) !== -1;
  }

  function getStoredWidth(setting) {
    try {
      var stored = localStorage.getItem(setting.storageKey);
      if (isAllowed(stored)) {
        return stored;
      }
      if (stored !== null) {
        localStorage.removeItem(setting.storageKey);
      }
    } catch (error) {}
    return null;
  }

  function getCurrentWidth(setting) {
    var current = document.documentElement.getAttribute(setting.attribute);
    return isAllowed(current) ? current : "compact";
  }

  function saveWidth(setting, width) {
    try {
      localStorage.setItem(setting.storageKey, width);
    } catch (error) {}
  }

  function syncButtons(setting, width) {
    var selector =
      "[data-docs-layout-width-target='" + setting.key + "'] " +
      "[data-docs-layout-width-value]";
    var buttons = document.querySelectorAll(selector);
    for (var i = 0; i < buttons.length; i += 1) {
      var button = buttons[i];
      var active = button.getAttribute("data-docs-layout-width-value") === width;
      button.setAttribute("aria-pressed", active ? "true" : "false");
    }
  }

  function applyWidth(setting, width, persist) {
    if (!isAllowed(width)) {
      width = "compact";
    }
    document.documentElement.setAttribute(setting.attribute, width);
    syncButtons(setting, width);
    if (persist) {
      saveWidth(setting, width);
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
    row.className = "docs-layout-widths__setting";
    row.setAttribute("data-docs-layout-width-target", setting.key);

    var label = document.createElement("span");
    label.className = "docs-layout-widths__label";
    label.textContent = setting.label;
    label.title = setting.title;
    row.appendChild(label);

    var options = document.createElement("div");
    options.className = "docs-layout-widths__options";
    options.setAttribute("role", "group");
    options.setAttribute("aria-label", setting.title);

    values.forEach(function (value) {
      var button = document.createElement("button");
      button.className = "docs-layout-widths__button";
      button.type = "button";
      button.textContent = valueLabels[value];
      button.title = setting.title + "：" + valueTitles[value];
      button.setAttribute("aria-label", setting.title + "：" + valueTitles[value]);
      button.setAttribute("aria-pressed", "false");
      button.setAttribute("data-docs-layout-width-value", value);
      button.addEventListener("click", function () {
        applyWidth(setting, value, true);
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
    trigger.title = "调整文档布局宽度";
    trigger.setAttribute("aria-label", "调整文档布局宽度");
    trigger.setAttribute("aria-haspopup", "true");
    trigger.setAttribute("aria-expanded", "false");

    var panel = document.createElement("div");
    panel.className = "docs-layout-widths__panel";
    panel.hidden = true;
    panel.setAttribute("role", "group");
    panel.setAttribute("aria-label", "文档布局宽度");

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
      applyWidth(setting, getStoredWidth(setting) || getCurrentWidth(setting), false);
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
    var switcher = event.target.closest("[data-md-component='docs-layout-widths']");
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
