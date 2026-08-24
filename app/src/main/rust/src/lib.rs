use adblock::{
    Engine,
    lists::{FilterSet, ParseOptions},
    request::Request,
    resources::{PermissionMask, Resource},
};
use jni::{
    JNIEnv,
    objects::{JClass, JObjectArray, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jlong, jstring},
};
use serde_json::json;
use std::{
    collections::{HashMap, HashSet},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Arc, LazyLock, Mutex},
    sync::atomic::{AtomicI64, Ordering},
};

static ENGINES: LazyLock<Mutex<HashMap<i64, Arc<Engine>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_ENGINE_ID: AtomicI64 = AtomicI64::new(1);

/// Android標準リストだけに与えるscriptlet実行権限。任意URLから追加されたリストは0のままにする。
const STANDARD_SCRIPTLET_PERMISSION: PermissionMask = PermissionMask::from_bits(0b0000_0001);

fn java_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value).ok().map(Into::into)
}

fn get_engine(handle: jlong) -> Option<Arc<Engine>> {
    ENGINES.lock().ok()?.get(&handle).cloned()
}

fn register_engine(engine: Engine) -> jlong {
    let handle = NEXT_ENGINE_ID.fetch_add(1, Ordering::Relaxed);
    match ENGINES.lock() {
        Ok(mut engines) => {
            engines.insert(handle, Arc::new(engine));
            handle
        }
        Err(_) => 0,
    }
}

fn parse_string_array(text: &str) -> Vec<String> {
    serde_json::from_str::<Vec<String>>(text).unwrap_or_default()
}

fn java_string_array(env: &mut JNIEnv, values: JObjectArray) -> Option<Vec<String>> {
    let length = env.get_array_length(&values).ok()?;
    let mut result = Vec::with_capacity(length as usize);
    for index in 0..length {
        let value = env.get_object_array_element(&values, index).ok()?;
        result.push(java_string(env, JString::from(value))?);
    }
    Some(result)
}

/**
 * Brave公式resources.jsonを読み込む。標準リストだけにscriptlet実行を許可するため、
 * scriptletとして注入可能な全resourceへ権限ビットを付ける。
 *
 * そのため、標準2リストはParseOptionsの同じビットを持つ一方、ユーザー追加リストは
 * network/cosmetic規則だけを使い、任意JavaScriptをページへ注入できない。
 */
fn load_scriptlet_resources(path: &str) -> Vec<Resource> {
    if path.trim().is_empty() {
        return Vec::new();
    }
    let Ok(json) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let Ok(mut resources) = serde_json::from_str::<Vec<Resource>>(&json) else {
        return Vec::new();
    };
    for resource in &mut resources {
        if resource.kind.supports_scriptlet_injection() {
            resource.permission = STANDARD_SCRIPTLET_PERMISSION;
        }
    }
    resources
}

fn build_engine(paths: Vec<String>, trusted_paths: HashSet<String>, scriptlet_resource_path: String) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let mut filter_set = FilterSet::new(false);
        let mut added = false;
        for path in paths {
            let Ok(rules) = std::fs::read_to_string(&path) else { continue };
            if rules.trim().is_empty() { continue; }
            let options = if trusted_paths.contains(&path) {
                ParseOptions {
                    permissions: STANDARD_SCRIPTLET_PERMISSION,
                    ..ParseOptions::default()
                }
            } else {
                ParseOptions::default()
            };
            filter_set.add_filter_list(rules, options);
            added = true;
        }
        if !added { return 0; }
        let mut engine = Engine::new_with_filter_set(filter_set);
        engine.use_resources(load_scriptlet_resources(&scriptlet_resource_path));
        register_engine(engine)
    })).unwrap_or(0)
}

/** Android側の巨大StringをJNI越しに複製せず、各フィルタファイルを順に読み込む。 */
fn create_engine_from_files(paths: Vec<String>, trusted_paths: Vec<String>, scriptlet_resource_path: String) -> jlong {
    build_engine(paths, trusted_paths.into_iter().collect(), scriptlet_resource_path)
}

/**
 * Engineの直列化データにはResourceStorageが含まれないため、復元後にも同じ公式resources.jsonを
 * 再接続する。これによりキャッシュ起動でもscriptlet注入が失われない。
 */
fn create_engine_from_serialized_file(path: String, scriptlet_resource_path: String) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(serialized) = std::fs::read(path) else { return 0 };
        if serialized.is_empty() { return 0; }
        let mut engine = Engine::default();
        if engine.deserialize(&serialized).is_err() { return 0; }
        engine.use_resources(load_scriptlet_resources(&scriptlet_resource_path));
        register_engine(engine)
    })).unwrap_or(0)
}

fn serialize_engine_to_file(handle: jlong, path: String) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = get_engine(handle) else { return false };
        let serialized = engine.serialize();
        !serialized.is_empty() && std::fs::write(path, serialized).is_ok()
    })).unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeCreateFromFiles(
    mut env: JNIEnv,
    _class: JClass,
    paths: JObjectArray,
    trusted_paths: JObjectArray,
    scriptlet_resource_path: JString,
) -> jlong {
    let (Some(paths), Some(trusted_paths), Some(scriptlet_resource_path)) = (
        java_string_array(&mut env, paths),
        java_string_array(&mut env, trusted_paths),
        java_string(&mut env, scriptlet_resource_path),
    ) else { return 0 };
    create_engine_from_files(paths, trusted_paths, scriptlet_resource_path)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeCreateFromSerializedFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    scriptlet_resource_path: JString,
) -> jlong {
    let (Some(path), Some(scriptlet_resource_path)) = (
        java_string(&mut env, path),
        java_string(&mut env, scriptlet_resource_path),
    ) else { return 0 };
    create_engine_from_serialized_file(path, scriptlet_resource_path)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeSerializeToFile(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) -> jboolean {
    let Some(path) = java_string(&mut env, path) else { return JNI_FALSE };
    if serialize_engine_to_file(handle, path) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut engines) = ENGINES.lock() {
        engines.remove(&handle);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jboolean {
    let (Some(url), Some(source_url), Some(resource_type)) = (
        java_string(&mut env, url),
        java_string(&mut env, source_url),
        java_string(&mut env, resource_type),
    ) else { return JNI_FALSE };
    let Some(engine) = get_engine(handle) else { return JNI_FALSE };
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(request) = Request::new(&url, &source_url, &resource_type, "get") else { return JNI_FALSE };
        if engine.check_network_request(&request).should_block() { JNI_TRUE } else { JNI_FALSE }
    })).unwrap_or(JNI_FALSE)
}

/// `should_block`だけではなく、Braveエンジンが返すredirectとremoveparamの有無も返す。
/// redirect本文はdata URLであり、Kotlin側が安全なresource type・MIME・サイズだけに限定して使う。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeNetworkDecisionJson(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jstring {
    let (Some(url), Some(source_url), Some(resource_type)) = (
        java_string(&mut env, url),
        java_string(&mut env, source_url),
        java_string(&mut env, resource_type),
    ) else {
        return env.new_string("{}").map_or(std::ptr::null_mut(), |value| value.into_raw());
    };
    let json = catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = get_engine(handle) else { return "{}".to_string() };
        let Ok(request) = Request::new(&url, &source_url, &resource_type, "get") else {
            return "{}".to_string();
        };
        let result = engine.check_network_request(&request);
        json!({
            "shouldBlock": result.should_block(),
            "redirectDataUrl": result.redirect,
            "rewrittenUrl": result.rewritten_url,
        }).to_string()
    })).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeCosmeticJson(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jstring {
    let url = java_string(&mut env, url).unwrap_or_default();
    let json = catch_unwind(AssertUnwindSafe(|| {
        get_engine(handle)
            .and_then(|engine| serde_json::to_string(&engine.url_cosmetic_resources(&url)).ok())
            .unwrap_or_else(|| "{}".to_string())
    })).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeGenericCss(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    classes_json: JString,
    ids_json: JString,
    exceptions_json: JString,
) -> jstring {
    let classes = java_string(&mut env, classes_json).map(|value| parse_string_array(&value)).unwrap_or_default();
    let ids = java_string(&mut env, ids_json).map(|value| parse_string_array(&value)).unwrap_or_default();
    let exceptions = java_string(&mut env, exceptions_json)
        .map(|value| parse_string_array(&value).into_iter().collect::<HashSet<_>>())
        .unwrap_or_default();
    let css = catch_unwind(AssertUnwindSafe(|| {
        get_engine(handle)
            .map(|engine| engine.hidden_class_id_selectors(&classes, &ids, &exceptions))
            .filter(|selectors| !selectors.is_empty())
            .map(|selectors| selectors.into_iter().take(500).collect::<Vec<_>>())
            .map(|selectors| format!("{}{{display:none!important;visibility:hidden!important;}}", selectors.join(",")))
            .unwrap_or_default()
    })).unwrap_or_default();
    env.new_string(css).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serialized_engine_preserves_network_blocking() {
        let mut filters = FilterSet::new(false);
        filters.add_filter_list("||ads.example.test^".to_string(), ParseOptions::default());
        let engine = Engine::new_with_filter_set(filters);
        let serialized = engine.serialize();
        assert!(!serialized.is_empty());

        let mut restored = Engine::default();
        restored.deserialize(&serialized).expect("serialized engine must restore");
        let request = Request::new(
            "https://ads.example.test/banner.js",
            "https://site.example.test/",
            "script",
            "get",
        ).expect("valid request");
        assert!(restored.check_network_request(&request).should_block());
    }

    #[test]
    fn network_result_exposes_removeparam_rewrite() {
        let mut filters = FilterSet::new(false);
        filters.add_filter_list(
            "||example.test^$removeparam=utm_source".to_string(),
            ParseOptions::default(),
        );
        let engine = Engine::new_with_filter_set(filters);
        let request = Request::new(
            "https://example.test/watch?utm_source=ad&keep=1",
            "https://origin.test/",
            "document",
            "get",
        ).expect("valid request");
        let result = engine.check_network_request(&request);
        assert!(!result.should_block());
        assert_eq!(
            result.rewritten_url.as_deref(),
            Some("https://example.test/watch?keep=1")
        );
    }

    #[test]
    fn standard_permission_mask_is_nonzero() {
        assert_eq!(STANDARD_SCRIPTLET_PERMISSION.to_bits(), 1);
    }
}
