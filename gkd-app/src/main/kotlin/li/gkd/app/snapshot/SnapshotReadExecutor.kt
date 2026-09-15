package li.gkd.app.snapshot

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A cancelled waiter never creates another thread or queues behind a stuck Binder call. */
class SnapshotReadExecutor {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SnapshotRootReader").apply { isDaemon = true }
    }

    suspend fun <T> read(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (!running.compareAndSet(false, true)) {
            continuation.resumeWith(Result.failure(SnapshotReadBusyException()))
            return@suspendCancellableCoroutine
        }
        try {
            executor.execute {
                val result = runCatching {
                    // Cancellation before dispatch must not start a needless Binder request.
                    if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                    block()
                }
                running.set(false)
                // A cancelled continuation discards late results; it cannot resume a capture.
                continuation.resumeWith(result)
            }
        } catch (e: Exception) {
            running.set(false)
            continuation.resumeWith(Result.failure(e))
        }
    }
}

class SnapshotReadBusyException : IllegalStateException("上次节点读取仍阻塞，请稍后重试或重新连接自动化服务")
