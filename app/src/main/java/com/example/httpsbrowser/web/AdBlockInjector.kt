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
                  if(!document.head || document.getElementById("$STYLE_ID")) return;
                  var style=document.createElement("style");
                  style.id="$STYLE_ID";
                  style.textContent=${org.json.JSONObject.quote(CSS)};
                  document.head.appendChild(style);
                })();
                $SCRIPT
            """.trimIndent(), null)
        }
    }
}
