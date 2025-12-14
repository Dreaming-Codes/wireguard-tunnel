//! JNI bridge for wireguard-tunnel Minecraft mod.
//!
//! Exposes WireGuard tunnel functionality to Java via JNI.

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use std::sync::Arc;

/// Stored JavaVM for attaching threads later.
static JAVA_VM: OnceCell<Arc<jni::JavaVM>> = OnceCell::new();

/// Initialize JNI - stores the JavaVM reference for later use.
///
/// Must be called once before any other native methods.
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_initJNI(
    env: JNIEnv,
    _class: JClass,
) {
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let _ = JAVA_VM.set(Arc::new(vm));
}

/// Simple ping function to verify native library is loaded correctly.
///
/// Returns a string confirming the library is working.
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_ping<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let output = env
        .new_string("wireguard_tunnel_jni OK")
        .expect("Failed to create Java string");
    output.into_raw()
}

/// Get the version of the native library.
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_version<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let version = env!("CARGO_PKG_VERSION");
    let output = env
        .new_string(version)
        .expect("Failed to create Java string");
    output.into_raw()
}
