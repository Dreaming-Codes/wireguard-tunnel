//! JNI bridge for wireguard-tunnel Minecraft mod.
//!
//! Exposes WireGuard tunnel functionality to Java via JNI.
//! Uses wireguard-netstack for userspace WireGuard with embedded TCP/IP stack.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::fs;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use thiserror::Error;
use tokio::runtime::{Handle, Runtime};
use warp_wireguard_gen::{get_config, register, RegistrationOptions, WarpCredentials};
use wireguard_netstack::{ManagedTunnel, NetStack, TcpConnection, WireGuardConfig};

// ============================================================================
// Error types
// ============================================================================

#[derive(Error, Debug)]
pub enum TunnelError {
    #[error("Tunnel not initialized")]
    NotInitialized,
    #[error("Tunnel already running")]
    AlreadyRunning,
    #[error("Tunnel not ready")]
    NotReady,
    #[error("WARP registration failed: {0}")]
    WarpRegistration(String),
    #[error("Credential persistence failed: {0}")]
    CredentialPersistence(String),
    #[error("Connection failed: {0}")]
    ConnectionFailed(String),
    #[error("Invalid handle: {0}")]
    InvalidHandle(i64),
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Timeout")]
    Timeout,
}

// ============================================================================
// WARP Credentials persistence
// ============================================================================

fn load_credentials(cred_path: &str) -> Result<WarpCredentials, TunnelError> {
    let content = fs::read_to_string(cred_path)
        .map_err(|e| TunnelError::CredentialPersistence(format!("Failed to read: {}", e)))?;
    serde_json::from_str(&content)
        .map_err(|e| TunnelError::CredentialPersistence(format!("Failed to parse: {}", e)))
}

fn save_credentials(cred_path: &str, credentials: &WarpCredentials) -> Result<(), TunnelError> {
    let path = PathBuf::from(cred_path);
    
    // Ensure parent directory exists
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| TunnelError::CredentialPersistence(format!("Failed to create dir: {}", e)))?;
    }

    let content = serde_json::to_string_pretty(credentials)
        .map_err(|e| TunnelError::CredentialPersistence(format!("Failed to serialize: {}", e)))?;
    fs::write(&path, content)
        .map_err(|e| TunnelError::CredentialPersistence(format!("Failed to write: {}", e)))?;

    log::info!("WARP credentials saved to {}", cred_path);
    Ok(())
}

/// MTU for the WireGuard tunnel.
/// Standard WireGuard MTU is 1420 (1500 - 80 bytes WG overhead).
/// This gives us a TCP MSS of ~1380 bytes, which works with Cloudflare Spectrum
/// and other proxies that may reject connections with unusually small MSS.
const WIREGUARD_MTU: u16 = 1420;

async fn load_or_register_warp(cred_path: &str) -> Result<(WireGuardConfig, WarpCredentials), TunnelError> {
    let path = PathBuf::from(cred_path);

    // Try to load existing credentials
    if path.exists() {
        match load_credentials(cred_path) {
            Ok(credentials) => {
                log::info!("Loaded existing WARP credentials from {}", cred_path);
                // Get fresh config using existing credentials
                match get_config(&credentials).await {
                    Ok(mut config) => {
                        // Set proper MTU for compatibility with proxied servers
                        config.mtu = Some(WIREGUARD_MTU);
                        log::info!("Using MTU {} for WireGuard tunnel", WIREGUARD_MTU);
                        return Ok((config, credentials));
                    }
                    Err(e) => {
                        log::warn!("Failed to get config with existing credentials: {}, re-registering", e);
                    }
                }
            }
            Err(e) => {
                log::warn!("Failed to load credentials: {}, registering new device", e);
            }
        }
    }

    // Register new WARP device
    log::info!("Registering new WARP device...");
    let (mut config, credentials) = register(RegistrationOptions::default())
        .await
        .map_err(|e| TunnelError::WarpRegistration(e.to_string()))?;

    // Set proper MTU for compatibility with proxied servers
    config.mtu = Some(WIREGUARD_MTU);
    log::info!("Using MTU {} for WireGuard tunnel", WIREGUARD_MTU);

    // Persist credentials
    save_credentials(cred_path, &credentials)?;

    log::info!("WARP device registered successfully");
    Ok((config, credentials))
}

// ============================================================================
// TCP Connection Handle Management
// ============================================================================

struct ConnectionManager {
    connections: RwLock<HashMap<i64, Arc<TcpConnection>>>,
    next_handle: AtomicI64,
}

impl ConnectionManager {
    fn new() -> Self {
        Self {
            connections: RwLock::new(HashMap::new()),
            next_handle: AtomicI64::new(1),
        }
    }

    fn insert(&self, conn: TcpConnection) -> i64 {
        let handle = self.next_handle.fetch_add(1, Ordering::SeqCst);
        self.connections.write().insert(handle, Arc::new(conn));
        handle
    }

    fn get(&self, handle: i64) -> Option<Arc<TcpConnection>> {
        self.connections.read().get(&handle).cloned()
    }

    fn remove(&self, handle: i64) -> Option<Arc<TcpConnection>> {
        self.connections.write().remove(&handle)
    }
}

// ============================================================================
// Tunnel State
// ============================================================================

#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum TunnelState {
    Stopped = 0,
    Starting = 1,
    Ready = 2,
    Failed = 3,
}

struct ActiveTunnel {
    #[allow(dead_code)]
    tunnel: ManagedTunnel,
    netstack: Arc<NetStack>,
}

// ============================================================================
// Global State
// ============================================================================

struct GlobalState {
    #[allow(dead_code)]
    runtime: Runtime,
    handle: Handle,
    tunnel: RwLock<Option<ActiveTunnel>>,
    connections: ConnectionManager,
}

impl GlobalState {
    fn new() -> Self {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("Failed to create Tokio runtime");

        let handle = runtime.handle().clone();

        Self {
            runtime,
            handle,
            tunnel: RwLock::new(None),
            connections: ConnectionManager::new(),
        }
    }

    fn netstack(&self) -> Result<Arc<NetStack>, TunnelError> {
        self.tunnel
            .read()
            .as_ref()
            .map(|t| t.netstack.clone())
            .ok_or(TunnelError::NotInitialized)
    }

    /// Run an async block on the runtime, safe to call from any thread.
    /// This spawns the future on the runtime and blocks until completion.
    fn run<F, T>(&self, future: F) -> T
    where
        F: std::future::Future<Output = T> + Send + 'static,
        T: Send + 'static,
    {
        // Spawn the future on the runtime and block on the result
        let (tx, rx) = std::sync::mpsc::channel();
        self.handle.spawn(async move {
            let result = future.await;
            let _ = tx.send(result);
        });
        rx.recv().expect("Runtime task panicked")
    }
}

static GLOBAL: OnceCell<GlobalState> = OnceCell::new();

fn global() -> &'static GlobalState {
    GLOBAL.get_or_init(GlobalState::new)
}

// ============================================================================
// JNI Helper Functions
// ============================================================================

fn throw_exception(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to get string: {}", e))
}

// ============================================================================
// JNI Functions - Initialization
// ============================================================================

/// Initialize JNI - stores the JavaVM reference for later use.
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_initJNI(
    env: JNIEnv,
    _class: JClass,
) {
    // Initialize env_logger for Rust logging (respects RUST_LOG env var)
    // Default to "info" level if RUST_LOG is not set
    let _ = env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info,wireguard_netstack=debug")
    ).try_init();
    
    let _ = env.get_java_vm().expect("Failed to get JavaVM");
    // Initialize global state (creates runtime)
    let _ = global();
    
    log::info!("WireGuard Tunnel JNI initialized");
}

/// Simple ping function to verify native library is loaded correctly.
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

// ============================================================================
// JNI Functions - Tunnel Lifecycle
// ============================================================================

/// Start the WARP tunnel.
/// 
/// @param credPath Path to store/load WARP credentials JSON
/// @return tunnel state (0=Stopped, 1=Starting, 2=Ready, 3=Failed)
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_startWarpTunnel<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cred_path: JString<'local>,
) -> jint {
    let cred_path = match get_string(&mut env, &cred_path) {
        Ok(s) => s,
        Err(e) => {
            throw_exception(&mut env, &e);
            return TunnelState::Failed as jint;
        }
    };

    // Check if already running
    {
        let tunnel_guard = global().tunnel.read();
        if tunnel_guard.is_some() {
            log::warn!("Tunnel already running");
            return TunnelState::Ready as jint;
        }
    }

    log::info!("Starting WARP tunnel with credentials from: {}", cred_path);

    let result = global().run(async move {
        // Load or register WARP credentials
        let (config, _credentials) = load_or_register_warp(&cred_path).await?;
        
        // Connect the managed tunnel
        log::info!("Connecting to WireGuard tunnel...");
        let tunnel = ManagedTunnel::connect(config)
            .await
            .map_err(|e| TunnelError::ConnectionFailed(e.to_string()))?;

        let netstack = tunnel.netstack();
        
        Ok::<_, TunnelError>(ActiveTunnel { tunnel, netstack })
    });

    match result {
        Ok(active_tunnel) => {
            *global().tunnel.write() = Some(active_tunnel);
            log::info!("WARP tunnel started successfully");
            TunnelState::Ready as jint
        }
        Err(e) => {
            log::error!("Failed to start tunnel: {}", e);
            throw_exception(&mut env, &format!("Failed to start tunnel: {}", e));
            TunnelState::Failed as jint
        }
    }
}

/// Get the current tunnel state.
/// 
/// @return 0=Stopped, 1=Starting, 2=Ready, 3=Failed
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tunnelState(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let tunnel_guard = global().tunnel.read();
    match tunnel_guard.as_ref() {
        Some(_) => TunnelState::Ready as jint,
        None => TunnelState::Stopped as jint,
    }
}

/// Shutdown the tunnel.
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_shutdownTunnel(
    _env: JNIEnv,
    _class: JClass,
) {
    log::info!("Shutting down WARP tunnel");

    // Close all connections (ensure shutdown happens on Tokio runtime)
    let handles: Vec<i64> = global()
        .connections
        .connections
        .read()
        .keys()
        .copied()
        .collect();

    let mut to_close = Vec::with_capacity(handles.len());
    for handle in handles {
        if let Some(conn) = global().connections.remove(handle) {
            to_close.push(conn);
        }
    }

    if !to_close.is_empty() {
        global().run(async move {
            for conn in to_close {
                conn.shutdown();
            }
        });
    }

    // Remove tunnel (ManagedTunnel handles cleanup in Drop)
    let tunnel = global().tunnel.write().take();
    if let Some(active) = tunnel {
        global().run(async move {
            active.tunnel.shutdown().await;
        });
    }

    log::info!("WARP tunnel shut down");
}

// ============================================================================
// JNI Functions - TCP Operations
// ============================================================================

/// Connect to a remote host via the tunnel.
/// 
/// @param host Hostname or IP address
/// @param port Port number
/// @param timeoutMs Connection timeout in milliseconds (0 = no timeout)
/// @return Connection handle (>0) on success, -1 on error
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tcpConnect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    _timeout_ms: jlong,
) -> jlong {
    let host = match get_string(&mut env, &host) {
        Ok(s) => s,
        Err(e) => {
            throw_exception(&mut env, &e);
            return -1;
        }
    };

    let netstack = match global().netstack() {
        Ok(ns) => ns,
        Err(e) => {
            throw_exception(&mut env, &format!("Tunnel not available: {}", e));
            return -1;
        }
    };

    let addr_str = format!("{}:{}", host, port);
    log::info!("Connecting to {} via WireGuard tunnel", addr_str);

    let result = global().run(async move {
        // Parse address - for now just try as IP:port
        let addr: SocketAddr = addr_str.parse()
            .map_err(|e| TunnelError::ConnectionFailed(format!("Invalid address {}: {}", addr_str, e)))?;
        
        let conn = TcpConnection::connect(netstack, addr)
            .await
            .map_err(|e| TunnelError::ConnectionFailed(e.to_string()))?;
        
        Ok::<_, TunnelError>(conn)
    });

    match result {
        Ok(conn) => {
            let handle = global().connections.insert(conn);
            log::debug!("TCP connection established, handle={}", handle);
            handle
        }
        Err(e) => {
            throw_exception(&mut env, &format!("Connection failed: {}", e));
            -1
        }
    }
}

/// Read data from a TCP connection.
/// 
/// @param handle Connection handle from tcpConnect
/// @param buffer Byte array to read into
/// @return Number of bytes read, 0 on EOF, -1 on error
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tcpRead<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    buffer: JByteArray<'local>,
) -> jint {
    let conn = match global().connections.get(handle) {
        Some(c) => c,
        None => {
            throw_exception(&mut env, &format!("Invalid handle: {}", handle));
            return -1;
        }
    };

    let buf_len = match env.get_array_length(&buffer) {
        Ok(len) => len as usize,
        Err(e) => {
            throw_exception(&mut env, &format!("Failed to get buffer length: {}", e));
            return -1;
        }
    };

    log::debug!("tcpRead: waiting for data on handle {}, buf_len={}", handle, buf_len);

    let result = global().run(async move {
        let mut rust_buf = vec![0u8; buf_len];
        
        // Check socket state before reading
        let can_recv = conn.netstack.can_recv(conn.handle);
        let may_recv = conn.netstack.may_recv(conn.handle);
        let state = conn.netstack.socket_state(conn.handle);
        log::debug!("tcpRead: socket state before read: can_recv={}, may_recv={}, state={:?}", 
                   can_recv, may_recv, state);
        
        match conn.read(&mut rust_buf).await {
            Ok(n) => {
                log::debug!("tcpRead: read returned {} bytes", n);
                Ok((n, rust_buf))
            }
            Err(e) => {
                log::error!("tcpRead: read returned error: {}", e);
                Err(e)
            }
        }
    });

    match result {
        Ok((0, _)) => {
            log::info!("tcpRead: returning EOF (0 bytes)");
            0
        }
        Ok((n, rust_buf)) => {
            log::debug!("tcpRead: returning {} bytes to Java", n);
            // Copy to Java array
            let bytes: Vec<i8> = rust_buf[..n].iter().map(|&b| b as i8).collect();
            if let Err(e) = env.set_byte_array_region(&buffer, 0, &bytes) {
                throw_exception(&mut env, &format!("Failed to copy to buffer: {}", e));
                return -1;
            }
            n as jint
        }
        Err(e) => {
            throw_exception(&mut env, &format!("Read error: {}", e));
            -1
        }
    }
}

/// Write data to a TCP connection.
/// 
/// @param handle Connection handle from tcpConnect
/// @param data Byte array to write
/// @param offset Offset in the array
/// @param length Number of bytes to write
/// @return Number of bytes written, -1 on error
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tcpWrite<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
    offset: jint,
    length: jint,
) -> jint {
    let conn = match global().connections.get(handle) {
        Some(c) => c,
        None => {
            throw_exception(&mut env, &format!("Invalid handle: {}", handle));
            return -1;
        }
    };

    // Get bytes from Java array
    let mut bytes = vec![0i8; length as usize];
    if let Err(e) = env.get_byte_array_region(&data, offset, &mut bytes) {
        throw_exception(&mut env, &format!("Failed to read from buffer: {}", e));
        return -1;
    }

    let rust_bytes: Vec<u8> = bytes.iter().map(|&b| b as u8).collect();
    log::debug!("tcpWrite: writing {} bytes to handle {}", rust_bytes.len(), handle);

    let result = global().run(async move {
        // Check socket state before writing
        let can_send = conn.netstack.can_send(conn.handle);
        let may_send = conn.netstack.may_send(conn.handle);
        let state = conn.netstack.socket_state(conn.handle);
        log::debug!("tcpWrite: socket state before write: can_send={}, may_send={}, state={:?}", 
                   can_send, may_send, state);
        
        let result = conn.write(&rust_bytes).await;
        
        // Poll after write to ensure packets are sent
        conn.netstack.poll();
        
        result
    });

    match result {
        Ok(n) => {
            log::debug!("tcpWrite: wrote {} bytes successfully", n);
            n as jint
        }
        Err(e) => {
            throw_exception(&mut env, &format!("Write error: {}", e));
            -1
        }
    }
}

/// Close a TCP connection.
/// 
/// @param handle Connection handle from tcpConnect
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tcpClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(conn) = global().connections.remove(handle) {
        global().run(async move {
            conn.shutdown();
        });
        log::debug!("TCP connection closed, handle={}", handle);
    }
}

/// Flush a TCP connection.
/// 
/// @param handle Connection handle from tcpConnect
/// @return 0 on success, -1 on error
#[no_mangle]
pub extern "system" fn Java_codes_dreaming_wireguard_jni_Native_tcpFlush<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    let conn = match global().connections.get(handle) {
        Some(c) => c,
        None => {
            throw_exception(&mut env, &format!("Invalid handle: {}", handle));
            return -1;
        }
    };

    // TcpConnection doesn't have an explicit flush - data is sent immediately.
    // NetStack::poll internally tokio::spawn()s, so it must run on a Tokio runtime.
    global().run(async move {
        conn.netstack.poll();
    });

    0
}
