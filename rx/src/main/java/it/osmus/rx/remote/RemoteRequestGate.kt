package it.osmus.rx.remote

import java.util.concurrent.locks.ReentrantLock

/**
 * The mono-TX relay processes one command at a time.
 * Prevent gallery thumbnails from saturating its finite pending-job queue.
 * A user-requested preview/download/list takes priority over thumbnails.
 */
internal object RemoteRequestGate {
    private val monitor = Object()
    private var active = false
    private var priorityWaiting = 0

    fun <T> run(priority: Boolean, work: () -> T): T {
        synchronized(monitor) {
            if (priority) priorityWaiting++
            try {
                while (active || (!priority && priorityWaiting > 0)) {
                    monitor.wait()
                }
                active = true
            } finally {
                if (priority) priorityWaiting--
            }
        }
        try {
            return work()
        } finally {
            synchronized(monitor) {
                active = false
                monitor.notifyAll()
            }
        }
    }
}
