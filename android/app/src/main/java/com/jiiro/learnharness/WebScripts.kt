package com.jiiro.learnharness

import java.util.Locale

/** JavaScript the shell runs in the page. Only ever injected into the configured server's origin. */
object WebScripts {
    /** Name of the androidx.webkit message listener object for blob downloads. */
    const val DOWNLOAD_BRIDGE = "lhAndroidDownloads"

    /**
     * Document-start script. The web app saves exports as `<a download href="blob:...">` and
     * revokes the blob right after click(), so the native DownloadListener could never read it.
     * This keeps a reference to each Blob for a minute and hands download clicks on blob links to
     * the app as base64.
     */
    val BLOB_DOWNLOADS = """
        (function () {
          if (window.__lhBlobDownloads) return;
          window.__lhBlobDownloads = true;
          var blobs = new Map();
          var create = URL.createObjectURL;
          URL.createObjectURL = function (obj) {
            var url = create.apply(URL, arguments);
            if (obj instanceof Blob) {
              blobs.set(url, obj);
              setTimeout(function () { blobs.delete(url); }, 60000);
            }
            return url;
          };
          function send(a) {
            var bridge = window.$DOWNLOAD_BRIDGE;
            var blob = bridge && a.href && a.href.indexOf('blob:') === 0 && blobs.get(a.href);
            if (!blob) return false;
            var reader = new FileReader();
            reader.onload = function () {
              var data = String(reader.result);
              bridge.postMessage(JSON.stringify({
                name: a.getAttribute('download') || '',
                mime: blob.type || 'application/octet-stream',
                data: data.slice(data.indexOf(',') + 1)
              }));
            };
            reader.readAsDataURL(blob);
            return true;
          }
          var click = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            if (this.hasAttribute('download') && send(this)) return;
            return click.apply(this, arguments);
          };
          document.addEventListener('click', function (e) {
            var a = e.target instanceof Element ? e.target.closest('a[download]') : null;
            if (a && send(a)) e.preventDefault();
          }, true);
        })();
    """.trimIndent()

    /** Name of the androidx.webkit message listener object for the pull-to-refresh guard. */
    const val PULL_BRIDGE = "lhAndroidPull"

    /**
     * Document-start script for pull-to-refresh. The page never scrolls the document (all
     * scrolling is in inner containers), so the WebView's own scrollY can't tell whether a pull
     * should refresh. On each single-finger touchstart this reports "1" when neither the touched
     * element nor any ancestor (crossing shadow roots) is scrolled down, else "0".
     */
    val PULL_GUARD = """
        (function () {
          if (window.__lhPullGuard) return;
          window.__lhPullGuard = true;
          function parent(e) {
            if (e.parentElement) return e.parentElement;
            var root = e.getRootNode ? e.getRootNode() : null;
            return root && root.host ? root.host : null;
          }
          function atTop(target) {
            for (var e = target instanceof Element ? target : null; e; e = parent(e)) {
              if (e.scrollTop > 0) return false;
            }
            var doc = document.scrollingElement;
            return !doc || doc.scrollTop <= 0;
          }
          window.addEventListener('touchstart', function (ev) {
            var bridge = window.$PULL_BRIDGE;
            if (!bridge) return;
            bridge.postMessage(ev.touches.length === 1 && atTop(ev.target) ? '1' : '0');
          }, { capture: true, passive: true });
        })();
    """.trimIndent()

    /**
     * Fallback for CSS env(safe-area-inset-*): the same insets, in CSS px, as custom properties
     * `--lh-safe-area-inset-{top,right,bottom,left}` on `<html>`'s inline style, which the phone
     * layout combines as `max(env(safe-area-inset-top), var(--lh-safe-area-inset-top, 0px))`.
     */
    fun safeAreaInsets(top: Float, right: Float, bottom: Float, left: Float): String {
        fun px(v: Float) = String.format(Locale.US, "'%.1fpx'", v)
        return "(function(s){s.setProperty('--lh-safe-area-inset-top',${px(top)});" +
            "s.setProperty('--lh-safe-area-inset-right',${px(right)});" +
            "s.setProperty('--lh-safe-area-inset-bottom',${px(bottom)});" +
            "s.setProperty('--lh-safe-area-inset-left',${px(left)});})(document.documentElement.style);"
    }
}
