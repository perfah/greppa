//! Plain-text logging for the LSP server: every log message goes to the
//! log file and stderr, never to stdout (which is reserved for the
//! JSON-RPC traffic). The raw JSON-RPC traffic itself is deliberately
//! not logged: the didOpen/didChange document sync sends the full text
//! of every opened and edited file, so logging the traffic would fill
//! the log with file content. A document event needs no more than its
//! file's URI, which the did_open and did_change handlers in main.rs
//! already log.

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::PathBuf;
use std::sync::Mutex;

lazy_static::lazy_static! {
    static ref LOG_FILE: Mutex<Option<File>> = Mutex::new({
        let path = log_file_path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match OpenOptions::new().create(true).append(true).open(&path) {
            Ok(file) => {
                eprintln!("[logging_io] log file: {}", path.display());
                Some(file)
            }
            Err(e) => {
                eprintln!("[logging_io] failed to open log file {}: {}", path.display(), e);
                None
            }
        }
    });
}

fn log_file_path() -> PathBuf {
    match std::env::var("GREPPA_LOG_PATH") {
        Ok(p) => PathBuf::from(p),
        Err(_) => {
            #[cfg(target_os = "windows")]
            {
                std::env::var("TEMP")
                    .map(PathBuf::from)
                    .unwrap_or_else(|_| PathBuf::from("C:\\temp"))
                    .join("greppa-lsp.log")
            }
            #[cfg(not(target_os = "windows"))]
            {
                PathBuf::from("/tmp/greppa-lsp.log")
            }
        }
    }
}

/// Logs a plain text message to the log file and stderr.
/// Never writes to stdout, because stdout is reserved for LSP traffic.
pub fn log_message(message: &str) {
    let timestamp = chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f");
    let line = format!("[{}] *** {}", timestamp, message);

    let mut stderr = std::io::stderr();
    let _ = writeln!(stderr, "{}", line);
    let _ = stderr.flush();

    if let Ok(mut guard) = LOG_FILE.lock() {
        if let Some(file) = guard.as_mut() {
            let _ = writeln!(file, "{}", line);
            let _ = file.flush();
        }
    }
}
