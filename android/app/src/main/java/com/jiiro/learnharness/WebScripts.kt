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

    /**
     * Fallback for CSS env(safe-area-inset-*): the same insets, in CSS px, as custom properties
     * `--lh-safe-area-inset-{top,right,bottom,left}` on :root. Use them as
     * `max(env(safe-area-inset-top), var(--lh-safe-area-inset-top, 0px))`.
     */
    fun safeAreaInsets(top: Float, right: Float, bottom: Float, left: Float): String {
        fun px(v: Float) = String.format(Locale.US, "%.1fpx", v)
        val css = ":root{--lh-safe-area-inset-top:${px(top)};--lh-safe-area-inset-right:${px(right)};" +
            "--lh-safe-area-inset-bottom:${px(bottom)};--lh-safe-area-inset-left:${px(left)}}"
        return """
            (function () {
              var s = document.getElementById('lh-android-insets');
              if (!s) {
                s = document.createElement('style');
                s.id = 'lh-android-insets';
                (document.head || document.documentElement).appendChild(s);
              }
              s.textContent = '$css';
            })();
        """.trimIndent()
    }

    /**
     * Pull-to-refresh guard: true only if nothing under the finger (page or inner scroller) is
     * scrolled down, so pulling inside a scrolled chat list scrolls it instead of reloading.
     */
    fun canPullToRefresh(x: Float, y: Float): String = String.format(
        Locale.US,
        "(function(x,y){if((window.scrollY||0)>0)return false;" +
            "for(var e=document.elementFromPoint(x,y);e;e=e.parentElement){if(e.scrollTop>0)return false;}" +
            "return true;})(%.1f,%.1f)",
        x, y,
    )
}
