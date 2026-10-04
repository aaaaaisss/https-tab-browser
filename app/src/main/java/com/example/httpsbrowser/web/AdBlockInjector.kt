package com.example.httpsbrowser.web

import android.webkit.WebView

object AdBlockInjector {
    private const val STYLE_ID = "__minimal_adblock_css"
    private const val CSS = """
        [class*="ad-"], [id*="ad-"],
        [class*="banner"], [id*="banner"],
        [class*="sponsor"], [id*="sponsor"],
        [class*="pr_"], [id*="pr_"], [data-ad],
        .adsbygoogle, .ad-container, .sponsored-content,
        .native-ad, .taboola-container, .outbrain-widget {
            display: none !important; height: 0 !important;
            width: 0 !important; visibility: hidden !important;
        }
    """

    private const val AGGRESSIVE_YOUTUBE_CSS = """
        #player-ads,.ytp-ad-overlay-container,.ytp-ad-module,
        ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-promoted-video-renderer,
        ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,
        ytd-action-companion-ad-renderer,ytm-ad-slot-renderer,
        ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,
        ytd-rich-item-renderer:has(> ytd-ad-slot-renderer),
        ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer),
        ytd-search-pyv-renderer.ytd-item-section-renderer,
        ytd-watch-next-secondary-results-renderer > ytd-ad-slot-renderer,
        ytd-rich-item-renderer > ytd-ad-slot-renderer,
        ytd-item-section-renderer > ytd-ad-slot-renderer,
        ytm-rich-item-renderer > ad-slot-renderer,
        lazy-list > ad-slot-renderer,
        ytm-companion-slot[data-content-type] > ytm-companion-ad-renderer,
        #masthead-ad.ytd-rich-grid-renderer,\n        .ytp-suggested-action > .ytp-suggested-action-badge,\n        yt-overlay-product-sticker {
            display:none !important;
        }
    """

    private const val SCRIPT = """
        (function(){
          if(window.__httpsBrowserMinimalAdBlockInstalled) return;
          window.__httpsBrowserMinimalAdBlockInstalled=true;
          const keys=new Set(["adPlacements","playerAds","adBreakHeartbeatParams","adSlots","adReasons","promoted","ypc_spin_up"]);
          function prune(o,d){
            if(!o || typeof o!=="object" || d>10) return;
            for(const k in o){ if(keys.has(k)){try{delete o[k]}catch(e){}} else prune(o[k],d+1); }
          }
          ["ytInitialPlayerResponse","ytInitialData"].forEach(function(name){
            let value;
            try{Object.defineProperty(window,name,{get:function(){return value;},set:function(v){prune(v,0);value=v;},configurable:true});}catch(e){}
          });
          const originalFetch=window.fetch;
          if(typeof originalFetch==="function"){
            window.fetch=async function(){
              const response=await originalFetch.apply(this,arguments);
              const first=arguments[0];
              const url=typeof first==="string"?first:(first&&first.url)||"";
              if(/youtubei\\/v1\\/(player|next)/.test(url)){
                try{const clone=response.clone();const json=await clone.json();prune(json,0);return new Response(JSON.stringify(json),{status:response.status,statusText:response.statusText,headers:response.headers});}catch(e){}
              }
              return response;
            };
          }
        })();
    """

    fun inject(webView: WebView, aggressive: Boolean) {
        if (!aggressive) return
        runCatching {
            webView.evaluateJavascript("""
                (function(){
                  if(!document.head) return;
                  function add(id,css){
                    if(document.getElementById(id)) return;
                    var style=document.createElement("style");
                    style.id=id;
                    style.textContent=css;
                    document.head.appendChild(style);
                  }
                  add("$STYLE_ID",${org.json.JSONObject.quote(CSS)});
                  if(location.hostname.indexOf("youtube.com")>=0 || location.hostname.indexOf("youtube-nocookie.com")>=0){
                    add("__youtube_aggressive_adblock_css",${org.json.JSONObject.quote(AGGRESSIVE_YOUTUBE_CSS)});
                  }
                })();
                $SCRIPT
            """.trimIndent(), null)
        }
    }
}
