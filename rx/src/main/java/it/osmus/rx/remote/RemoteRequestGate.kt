package it.osmus.rx.remote

/**
 * The mono-TX relay processes one command at a time.
 * Prevent gallery thumbnails from saturating its finite pending-job queue.
 * A user-requested preview/download/list takes priority over thumbnails.
 */
internal object RemoteRequestGate {
    private val monitor = Object()
    private var active = false
    private var priorityWaiting = 0
    private var transferActive = false

    /** Pause low-priority thumbnails for the whole file transfer. */
    fun setTransferActive(value: Boolean) {
        synchronized(monitor) {
            transferActive = value
            monitor.notifyAll()
        }
    }

    fun <T> run(priority: Boolean, work: () -> T): T {
        synchronized(monitor) {
            if (priority) priorityWaiting++
            try {
                while (active || (!priority && (priorityWaiting > 0 || transferActive))) {
                    monitor.wait()
                }
                active = true
            } finally {
                if (priority) {
                    priorityWaiting--
                    monitor.notifyAll()
                }
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
