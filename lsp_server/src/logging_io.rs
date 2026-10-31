//! AsyncRead/AsyncWrite wrappers that log every byte that passes through.
//! Used to observe raw JSON-RPC traffic reaching and leaving the LSP server.

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Mutex;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

lazy_static::lazy_static! {
    static ref LOG_FILE: Mutex<Option<File>> = Mutex::new({
        let path = log_file_path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match OpenOptions::new().create(true).append(true).open(&path) {
            Ok(file) => {
                eprintln!("[logging_io] LSP traffic log file: {}", path.display());
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
    match std::env::var("ANTIVIBER_LOG_PATH") {
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

/// Wraps an AsyncRead and logs every chunk of bytes read from it.
pub struct LoggingRead<R> {
    inner: R,
}

impl<R> LoggingRead<R> {
    pub fn new(inner: R) -> Self {
        Self { inner }
    }
}

impl<R: AsyncRead + Unpin> AsyncRead for LoggingRead<R> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let filled_before = buf.filled().len();
        let result = Pin::new(&mut self.inner).poll_read(cx, buf);

        if let Poll::Ready(Ok(())) = &result {
            let filled_after = buf.filled().len();
            if filled_after > filled_before {
                let read = &buf.filled()[filled_before..filled_after];
                log_traffic("INBOUND JSON-RPC", read);
            }
        }

        result
    }
}

/// Wraps an AsyncWrite and logs every chunk of bytes written to it.
pub struct LoggingWrite<W> {
    inner: W,
}

impl<W> LoggingWrite<W> {
    pub fn new(inner: W) -> Self {
        Self { inner }
    }
}

impl<W: AsyncWrite + Unpin> AsyncWrite for LoggingWrite<W> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let result = Pin::new(&mut self.inner).poll_write(cx, buf);

        if let Poll::Ready(Ok(n)) = &result {
            if *n > 0 {
                log_traffic("OUTBOUND JSON-RPC", &buf[..*n]);
            }
        }

        result
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// Writes a labeled, timestamped traffic block to the log file and stderr.
/// Stdout is never touched.
fn log_traffic(label: &str, bytes: &[u8]) {
    let text = String::from_utf8_lossy(bytes);
    let timestamp = chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f");

    let mut stderr = std::io::stderr();
    let _ = writeln!(stderr, "===== [{}] {} =====", timestamp, label);
    let _ = writeln!(stderr, "{}", text);
    let _ = writeln!(stderr, "===== END {} =====", label);
    let _ = stderr.flush();

    if let Ok(mut guard) = LOG_FILE.lock() {
        if let Some(file) = guard.as_mut() {
            let _ = writeln!(file, "===== [{}] {} =====", timestamp, label);
            let _ = writeln!(file, "{}", text);
            let _ = writeln!(file, "===== END {} =====", label);
            let _ = file.flush();
        }
    }
}
