package com.example.httpsbrowser.web

/**
 * YouTube-specific document-start scripts recovered from the APK.
 *
 * These scripts are intentionally kept separate from BrowserWebViewRegistry so the
 * browser lifecycle code stays readable and the recovered JavaScript can be compared
 * directly with APK string evidence.
 */
object YoutubeAdScripts {
    val noAdWarmPlayer = """
        (function(){
          if(window.__nekoBrowserNoAdWarmPlayer) return;
          window.__nekoBrowserNoAdWarmPlayer=true;
          function patchText(text){
            if(typeof text!=='string'||text.indexOf('contentPlaybackContext')<0||text.indexOf('isInlinePlaybackNoAd')>=0) return text;
            return text.replace(/"contentPlaybackContext"\s*:\s*\{(?!\s*"isInlinePlaybackNoAd"\s*:\s*true)/,'"contentPlaybackContext":{"isInlinePlaybackNoAd":true,');
          }
          function patchCarrier(value){
            try{ if(value&&typeof value.body==='string') value.body=patchText(value.body); }catch(_e){}
            return value;
          }
          try{
            var realStringify=JSON.stringify;
            JSON.stringify=function(){return patchText(realStringify.apply(this,arguments));};
          }catch(_e){}
          try{
            var realAssign=Object.assign;
            Object.assign=new Proxy(realAssign,{apply:function(target,thisArg,args){
              var result=Reflect.apply(target,thisArg,args);
              patchCarrier(result); if(args&&args.length>0) patchCarrier(args[0]); return result;
            }});
          }catch(_e){}
        })();
    """.trimIndent()

    val sabrPatchOnly = """
        (function(){
          if(window.__nekoBrowserSabrPatchOnly) return;
          window.__nekoBrowserSabrPatchOnly=true;
          var realFetch=window.fetch;
          if(typeof realFetch!=='function') return;
          var premiumCached=null;
          function isPremium(){
            if(premiumCached!==null) return premiumCached;
            var logo=document.querySelector('a#logo[title]');
            if(!logo) return false;
            premiumCached=/premium/i.test(logo.getAttribute('title')||'');
            return premiumCached;
          }
          function readSmall(reader){
            var chunks=[],total=0;
            return reader.read().then(function pump(result){
              if(result.done){
                var merged=new Uint8Array(total),offset=0;
                for(var i=0;i<chunks.length;i++){merged.set(chunks[i],offset);offset+=chunks[i].length;}
                return merged;
              }
              chunks.push(result.value);total+=result.value.length;
              if(total>=1000){try{reader.cancel();}catch(_e){}return null;}
              return reader.read().then(pump);
            });
          }
          function patchBackoff(bytes){
            var patched=false;
            for(var i=0;i<bytes.length-2;i++){
              if(bytes[i]!==0x20) continue;
              var value=0,shift=0,end=i+1;
              while(end<bytes.length&&shift<35){
                value|=(bytes[end]&0x7f)<<shift;
                if(!(bytes[end]&0x80)){end++;break;}
                shift+=7;end++;
              }
              if(value>500&&value<100000){
                var target=50+Math.floor(Math.random()*100),pos=i+1,remaining=target;
                while(pos<end-1){bytes[pos++]=(remaining&0x7f)|0x80;remaining>>>=7;}
                bytes[pos]=remaining&0x7f;patched=true;
              }
            }
            return patched;
          }
          function isSabrControlUrl(url){return url.indexOf('googlevideo.com')>=0&&url.indexOf('sabr=1')>=0;}
          function patchArrayBuffer(buffer){
            if(!buffer||!buffer.byteLength||buffer.byteLength>=1000) return buffer;
            var bytes=new Uint8Array(buffer.slice(0));
            return patchBackoff(bytes)?bytes.buffer:buffer;
          }
          window.fetch=function(resource,init){
            var url=typeof resource==='string'?resource:(resource&&resource.url)||'';
            if(!isSabrControlUrl(url)||isPremium()) return realFetch.apply(this,arguments);
            return realFetch.apply(this,arguments).then(function(response){
              if(!response.ok||!response.body) return response;
              var pass,scan,reinit;
              try{
                var streams=response.body.tee();pass=streams[0];scan=streams[1];
                reinit={status:response.status,statusText:response.statusText,headers:response.headers};
              }catch(_e){return response;}
              function passThrough(){return new Response(pass,reinit);}
              return readSmall(scan.getReader()).then(function(bytes){
                if(bytes===null) return passThrough();
                patchBackoff(bytes);
                var out=new Response(bytes,reinit);
                try{Object.defineProperty(out,'url',{value:response.url,configurable:true});Object.defineProperty(out,'type',{value:response.type,configurable:true});}catch(_e){}
                return out;
              }).catch(function(){
                return passThrough();
              });
            });
          };
          try{
            var xhrProto=typeof XMLHttpRequest==='function'&&XMLHttpRequest.prototype;
            if(xhrProto){
              var realOpen=xhrProto.open,realSend=xhrProto.send;
              xhrProto.open=function(method,url){
                this.__nekoSabrControl=isSabrControlUrl(String(url||''));
                return realOpen.apply(this,arguments);
              };
              xhrProto.send=function(){
                if(this.__nekoSabrControl&&!isPremium()){
                  try{
                    var object=this,proto=object,getter=null;
                    while(proto&&!getter){
                      var descriptor=Object.getOwnPropertyDescriptor(proto,'response');
                      getter=descriptor&&descriptor.get;
                      proto=Object.getPrototypeOf(proto);
                    }
                    var initialResponse=object.response,lastOriginal=null,lastPatched=null;
                    Object.defineProperty(object,'response',{configurable:true,get:function(){
                      var original=getter?getter.call(object):initialResponse;
                      if(!(original instanceof ArrayBuffer)) return original;
                      if(original===lastOriginal&&lastPatched!==null) return lastPatched;
                      lastOriginal=original;lastPatched=patchArrayBuffer(original);return lastPatched;
                    }});
                  }catch(_e){}
                }
                return realSend.apply(this,arguments);
              };
            }
          }catch(_e){}
        })();
    """.trimIndent()

    val adSanitizer = """
        (function(){
          if(window.__nekoBrowserYoutubeAdSanitizer) return;
          window.__nekoBrowserYoutubeAdSanitizer=true;
          const AD_KEYS = new Set([
              'adPlacements', 'playerAds', 'adBreakHeartbeatParams',
              'adSlots', 'adReasons', 'promoted', 'ypc_spin_up'
          ]);
          function pruneAds(obj, depth){
            if(!obj || typeof obj!=='object' || depth>10) return;
            for(const key in obj){
              if(AD_KEYS.has(key)) delete obj[key];
              else pruneAds(obj[key], depth+1);
            }
          }
          ['ytInitialPlayerResponse','ytInitialData'].forEach(function(name){
            let val;
            try{
              Object.defineProperty(window,name,{
                get:function(){return val;},
                set:function(v){pruneAds(v,0);val=v;},
                configurable:true
              });
            }catch(_e){}
          });
          var origFetch=window.fetch;
          if(typeof origFetch==='function'){
            window.fetch=async function(){
              const args=arguments;
              const res=await origFetch.apply(this,args);
              const url=typeof args[0]==='string'?args[0]:(args[0]&&args[0].url)||'';
              if(/youtubei\/v1\/(player|next)/.test(url)){
                try{
                  const clone=res.clone();
                  const json=await clone.json();
                  pruneAds(json,0);
                  return new Response(JSON.stringify(json),{
                    status:res.status,statusText:res.statusText,headers:res.headers
                  });
                }catch(_e){}
              }
              return res;
            };
          }
        })();
    """.trimIndent()
}
