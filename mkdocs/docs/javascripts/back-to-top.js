(function () {
  var observer = null;
  var syncing = false;

  function syncBackToTopButton() {
    var button = document.querySelector("[data-md-component='top']");
    if (!button) {
      return;
    }

    syncing = true;
    var visible = window.scrollY > 0;
    button.toggleAttribute("hidden", !visible);
    syncing = false;
  }

  function mountBackToTopButton() {
    var button = document.querySelector("[data-md-component='top']");
    if (!button) {
      return;
    }

    if (observer) {
      observer.disconnect();
    }

    observer = new MutationObserver(function () {
      if (!syncing) {
        syncBackToTopButton();
      }
    });
    observer.observe(button, { attributes: true, attributeFilter: ["hidden"] });

    syncBackToTopButton();
    window.removeEventListener("scroll", syncBackToTopButton);
    window.addEventListener("scroll", syncBackToTopButton, { passive: true });
  }

  if (typeof document$ !== "undefined") {
    document$.subscribe(mountBackToTopButton);
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mountBackToTopButton);
  } else {
    mountBackToTopButton();
  }
})();
