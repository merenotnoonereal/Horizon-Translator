
/// `CompletableFuture`: `supplyAsync` runs on a thread of its own; `thenAcceptAsync` waits for the value on
/// another, then (after the delayed executor's delay) hands it on. A supplier that panics completes nothing.
pub struct JFuture<T>(std::sync::Arc<(std::sync::Mutex<Option<Option<T>>>, std::sync::Condvar)>);

impl<T> Clone for JFuture<T> {
    fn clone(&self) -> Self {
        JFuture(self.0.clone())
    }
}

impl<T> std::fmt::Debug for JFuture<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("JFuture")
    }
}

impl<T: Clone + Send + 'static> JFuture<T> {
    pub fn supply_async(f: impl FnOnce() -> T + Send + 'static) -> Self {
        let fut = JFuture(std::sync::Arc::new((std::sync::Mutex::new(None), std::sync::Condvar::new())));
        let shared = fut.0.clone();
        std::thread::spawn(move || {
            let v = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).ok();
            *shared.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(v);
            shared.1.notify_all();
        });
        fut
    }

    /// `thenAcceptAsync(f, CompletableFuture.delayedExecutor(delay, unit))`.
    pub fn then_accept_after(&self, delay: std::time::Duration, f: impl FnOnce(T) + Send + 'static) {
        let shared = self.0.clone();
        std::thread::spawn(move || {
            let mut done = shared.0.lock().unwrap_or_else(|e| e.into_inner());
            while done.is_none() {
                done = shared.1.wait(done).unwrap_or_else(|e| e.into_inner());
            }
            let v = done.clone().flatten();
            drop(done);
            if let Some(v) = v {
                std::thread::sleep(delay);
                f(v);
            }
        });
    }

    /// `join()`: waits for the value (a supplier that panicked panics here too).
    pub fn join(&self) -> T {
        let mut done = self.0.0.lock().unwrap_or_else(|e| e.into_inner());
        while done.is_none() {
            done = self.0.1.wait(done).unwrap_or_else(|e| e.into_inner());
        }
        done.clone().flatten().expect("the future's supplier failed")
    }
}

#[cfg(test)]
mod jfuture_tests {
    use super::*;

    #[test]
    fn supplies_then_accepts() {
        let f = JFuture::supply_async(|| 41 + 1);
        let (tx, rx) = std::sync::mpsc::channel();
        f.then_accept_after(std::time::Duration::from_millis(1), move |v| tx.send(v).unwrap());
        assert_eq!(rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap(), 42);
        assert_eq!(f.join(), 42);
    }
}
