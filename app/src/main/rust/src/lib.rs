use adblock::{
    Engine,
    lists::{FilterSet, ParseOptions},
    request::Request,
};
use jni::{
    JNIEnv,
    objects::{JClass, JObjectArray, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jlong, jstring},
};
use std::{
    collections::{HashMap, HashSet},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Arc, LazyLock, Mutex},
    sync::atomic::{AtomicI64, Ordering},
};

static ENGINES: LazyLock<Mutex<HashMap<i64, Arc<Engine>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_ENGINE_ID: AtomicI64 = AtomicI64::new(1);

fn java_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value).ok().map(Into::into)
}

fn get_engine(handle: jlong) -> Option<Arc<Engine>> {
    ENGINES.lock().ok()?.get(&handle).cloned()
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
 * Android 側の巨大な全規則StringをJNI越しに複製しない。各ファイルを順に読み、
 * FilterSetへ追加したら直ちに次へ進むことで、初期コンパイル時のピークメモリを抑える。
 */
fn create_engine_from_files(paths: Vec<String>) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let mut filter_set = FilterSet::new(false);
        let mut added = false;
        for path in paths {
            let Ok(rules) = std::fs::read_to_string(path) else { continue };
            if rules.trim().is_empty() { continue; }
            filter_set.add_filter_list(rules, ParseOptions::default());
            added = true;
        }
        if !added { return 0; }
        let engine = Arc::new(Engine::new_with_filter_set(filter_set));
        let handle = NEXT_ENGINE_ID.fetch_add(1, Ordering::Relaxed);
        match ENGINES.lock() {
            Ok(mut engines) => {
                engines.insert(handle, engine);
                handle
            }
            Err(_) => 0,
        }
    })).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_httpsbrowser_data_NativeAdBlockEngine_nativeCreateFromFiles(
    mut env: JNIEnv,
    _class: JClass,
    paths: JObjectArray,
) -> jlong {
    let Some(paths) = java_string_array(&mut env, paths) else { return 0 };
    create_engine_from_files(paths)
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
            // DOMに偶然一致する大量のgeneric selectorを一括注入してWebView rendererを圧迫しない。
            .map(|selectors| selectors.into_iter().take(500).collect::<Vec<_>>())
            .map(|selectors| format!("{}{{display:none!important;visibility:hidden!important;}}", selectors.join(",")))
            .unwrap_or_default()
    })).unwrap_or_default();
    env.new_string(css).map_or(std::ptr::null_mut(), |value| value.into_raw())
}
